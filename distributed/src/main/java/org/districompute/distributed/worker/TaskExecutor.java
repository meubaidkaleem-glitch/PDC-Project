package org.districompute.distributed.worker;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.CancellationSource;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.execution.TaskCancelledException;
import org.districompute.core.partition.PartialResultMessage;
import org.districompute.core.partition.PartitionWorker;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.Payload;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.ResultPayload;
import org.districompute.distributed.protocol.TaskAcceptedPayload;
import org.districompute.distributed.protocol.TaskPayload;
import org.districompute.distributed.protocol.TaskProgressPayload;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.resource.CapabilityProbe;
import org.districompute.distributed.simulation.FaultInjector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admits and executes attempts on a fixed pool of platform threads, through Core's {@link PartitionWorker}. The
 * worker never computes anything itself: decoding, the kernel and encoding are Core's.
 *
 * <p>Every admitted attempt ends with exactly one message: {@code RESULT}, {@code ERROR TASK_FAILED} or
 * {@code ERROR TASK_CANCELLED}. If the connection the attempt came from is gone, nothing is sent: the coordinator
 * has already treated the attempt as lost.
 */
final class TaskExecutor implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskExecutor.class);
    private static final int REMEMBERED_ATTEMPTS = 4096;

    /** Sends messages over the connection an attempt arrived on. */
    interface Outbox {

        boolean send(Envelope message);

        boolean isOpen();
    }

    private final class Running {
        private final AttemptRef ref;
        private final int partitionIndex;
        private final CancellationSource cancellation = new CancellationSource();
        private final Outbox outbox;
        private final long receivedNanos;
        private final long admittedNanos = System.nanoTime();
        private volatile ScheduledFuture<?> timeout;

        Running(AttemptRef ref, int partitionIndex, Outbox outbox, long receivedNanos) {
            this.ref = ref;
            this.partitionIndex = partitionIndex;
            this.outbox = outbox;
            this.receivedNanos = receivedNanos;
        }
    }

    private final WorkerConfig config;
    private final PartitionWorker partitionWorker;
    private final ThreadPoolExecutor pool;
    private final ScheduledExecutorService timers;
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final Map<String, Boolean> finished = new LinkedHashMap<>(256, 0.75f, false) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_ATTEMPTS;
        }
    };
    private final AtomicInteger active = new AtomicInteger();
    /**
     * Makes "admit an attempt and queue its TASK_ACCEPTED" atomic with "list the running attempts and queue a
     * heartbeat", so a heartbeat always lists every attempt accepted before it on the connection.
     */
    private final Object admission = new Object();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile int capacity;
    private volatile boolean accepting = true;

    private final FaultInjector faults;

    TaskExecutor(WorkerConfig config, FaultInjector faults) {
        this.config = config;
        this.faults = faults;
        this.partitionWorker = new PartitionWorker(config.registry(), config.workerId());
        this.capacity = config.maxConcurrentTasks();
        AtomicInteger threadNumber = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(capacity, capacity, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                runnable -> Thread.ofPlatform().name("worker-kernel-" + threadNumber.incrementAndGet()).daemon(true)
                        .unstarted(runnable));
        this.timers = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("worker-timers").daemon(true).unstarted(runnable));
    }

    /**
     * Admits or rejects a {@code TASK} and, if admitted, starts it. Runs on the connection's reader thread; the
     * reply ({@code TASK_ACCEPTED} or {@code ERROR}) is queued before execution starts, so it always precedes the
     * attempt's final message.
     *
     * @param task the message
     * @param receivedNanos when its frame arrived
     * @param outbox the connection it arrived on
     */
    void submit(Envelope task, long receivedNanos, Outbox outbox) {
        TaskPayload payload = task.payload(TaskPayload.class);
        WorkUnitMessage unit = payload.workUnit();
        AttemptRef ref = task.attemptRef();
        Optional<Refusal> refusal = refusal(unit, ref);
        if (refusal.isPresent()) {
            outbox.send(reply(MessageType.ERROR, ref, task.messageId(), new ErrorPayload(
                    ProtocolErrorCode.TASK_REJECTED, refusal.get().message(), refusal.get().retryable(), false, null)));
            LOG.info("Rejected {}: {}", ref.attemptId(), refusal.get().message());
            return;
        }
        Running attempt = new Running(ref, unit.descriptor().index(), outbox, receivedNanos);
        synchronized (admission) {
            running.put(ref.attemptId(), attempt);
            int now = active.incrementAndGet();
            try {
                attempt.timeout = timers.schedule(() -> attempt.cancellation.cancel(CancellationReason.TIMEOUT),
                        payload.timeoutMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // The worker stopped after the admission check: refuse instead of admitting.
                forget(attempt);
                outbox.send(reply(MessageType.ERROR, ref, task.messageId(), new ErrorPayload(
                        ProtocolErrorCode.TASK_REJECTED, "Worker " + config.workerId() + " is stopping", true, false,
                        null)));
                return;
            }
            // Queued before execution starts, so it always precedes the attempt's final message.
            outbox.send(reply(MessageType.TASK_ACCEPTED, ref, task.messageId(),
                    new TaskAcceptedPayload(now, capacity)));
        }
        try {
            pool.execute(() -> run(attempt, unit));
        } catch (RejectedExecutionException e) {
            forget(attempt);
            attempt.timeout.cancel(false);
            outbox.send(reply(MessageType.ERROR, ref, null, new ErrorPayload(ProtocolErrorCode.TASK_CANCELLED,
                    "Worker " + config.workerId() + " is stopping", true, false, null)));
        }
    }

    /**
     * Builds and queues a heartbeat atomically with admissions (see {@link #admission}).
     *
     * @param heartbeat receives the running attempt ids and queues the heartbeat
     */
    void heartbeat(java.util.function.Consumer<List<String>> heartbeat) {
        synchronized (admission) {
            heartbeat.accept(runningAttempts());
        }
    }

    private void forget(Running attempt) {
        running.remove(attempt.ref.attemptId());
        active.decrementAndGet();
    }

    /**
     * Cancels an attempt at the coordinator's request.
     *
     * @param attemptId the attempt
     * @return {@code true} if it was running
     */
    boolean cancel(String attemptId) {
        Running attempt = running.get(attemptId);
        if (attempt == null) {
            return false;
        }
        attempt.cancellation.cancel(CancellationReason.USER_REQUESTED);
        return true;
    }

    /**
     * Whether an attempt finished recently on this worker.
     *
     * @param attemptId the attempt
     * @return {@code true} if it is remembered as finished
     */
    boolean recentlyFinished(String attemptId) {
        synchronized (finished) {
            return finished.containsKey(attemptId);
        }
    }

    /**
     * Cancels every running attempt, for example because their connection is gone.
     *
     * @param reason the reason logged
     */
    void cancelAll(String reason) {
        if (!running.isEmpty()) {
            LOG.info("Cancelling {} running attempts: {}", running.size(), reason);
        }
        running.values().forEach(a -> a.cancellation.cancel(CancellationReason.SHUTDOWN));
    }

    /** Stops admitting attempts (graceful shutdown). */
    void stopAccepting() {
        accepting = false;
    }

    /**
     * Waits until no attempt is running.
     *
     * @param timeout how long to wait
     * @return {@code true} if idle in time
     */
    boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (active.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Changes the concurrency limit at run time; reported to the coordinator in the next heartbeat.
     *
     * @param newCapacity attempts at the same time
     */
    synchronized void setCapacity(int newCapacity) {
        if (newCapacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        if (newCapacity > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(newCapacity);
            pool.setCorePoolSize(newCapacity);
        } else {
            pool.setCorePoolSize(newCapacity);
            pool.setMaximumPoolSize(newCapacity);
        }
        capacity = newCapacity;
    }

    int capacity() {
        return capacity;
    }

    int active() {
        return active.get();
    }

    long completed() {
        return completed.get();
    }

    long failed() {
        return failed.get();
    }

    List<String> runningAttempts() {
        return new ArrayList<>(running.keySet());
    }

    @Override
    public void close() {
        accepting = false;
        cancelAll("worker stopped");
        pool.shutdownNow();
        timers.shutdownNow();
    }

    /** Why an attempt is refused, and whether another worker (or a later retry) could run it. */
    private record Refusal(String message, boolean retryable) {
    }

    private Optional<Refusal> refusal(WorkUnitMessage unit, AttemptRef ref) {
        if (!accepting) {
            return Optional.of(new Refusal("Worker " + config.workerId() + " is shutting down", true));
        }
        if (running.containsKey(ref.attemptId()) || recentlyFinished(ref.attemptId())) {
            // A coordinator never reuses attempt ids, so running it again would mean executing twice.
            return Optional.of(new Refusal("Duplicate attempt " + ref.attemptId() + ": it was already received",
                    false));
        }
        if (!CapabilityProbe.allowed(config.allowedAlgorithms(), unit.descriptor().algorithm().id())) {
            return Optional.of(new Refusal("Algorithm " + unit.descriptor().algorithm().id()
                    + " is not offered by this worker", true));
        }
        ComputeAlgorithm<?, ?> algorithm;
        try {
            algorithm = config.registry().resolve(unit.descriptor().algorithm());
        } catch (DistriComputeException e) {
            return Optional.of(new Refusal(e.getMessage(), true));
        }
        if (!(algorithm instanceof PartitionedAlgorithm<?, ?, ?, ?>)) {
            return Optional.of(new Refusal(unit.descriptor().algorithm() + " cannot be partitioned on this worker",
                    true));
        }
        if (unit.payload().sizeBytes() > config.maxTaskMemoryBytes()) {
            return Optional.of(new Refusal("The work unit needs at least " + unit.payload().sizeBytes()
                    + " bytes; the limit per attempt is " + config.maxTaskMemoryBytes(), true));
        }
        if (active.get() >= capacity) {
            return Optional.of(new Refusal("Worker is at its limit of " + capacity + " concurrent tasks", true));
        }
        return Optional.empty();
    }

    private void run(Running attempt, WorkUnitMessage unit) {
        long started = System.nanoTime();
        FaultInjector.Behaviour behaviour = faults.behaviour(attempt.ref.attemptId());
        Envelope last;
        Envelope duplicate = null;
        try {
            attempt.cancellation.throwIfCancelled();
            if (behaviour == FaultInjector.Behaviour.HANG) {
                hang(attempt);
                return;
            }
            if (behaviour == FaultInjector.Behaviour.FAIL) {
                throw DistriComputeException.forPartition(ErrorCode.PARTITION_FAILED, attempt.partitionIndex,
                        "Simulated kernel failure on " + config.workerId() + " (fault simulation)", null);
            }
            PartialResultMessage partial = partitionWorker.execute(unit, attempt.cancellation,
                    new ThrottledProgress(attempt));
            long extra = faults.extraKernelNanos(partial.computeNanos());
            if (extra > 0) {
                // A slower machine: the kernel really takes longer, and reports the longer time.
                pause(extra, attempt);
                partial = withComputeNanos(partial, partial.computeNanos() + extra);
            }
            if (faults.responseDelayMillis() > 0) {
                pause(faults.responseDelayMillis() * 1_000_000L, attempt);
            }
            if (behaviour == FaultInjector.Behaviour.CORRUPT) {
                partial = corrupted(partial);
            }
            long ended = System.nanoTime();
            WorkerTiming timing = new WorkerTiming(started - attempt.admittedNanos, ended - started,
                    ended - attempt.receivedNanos);
            completed.incrementAndGet();
            // A result is useful even if a CANCEL raced with it: the coordinator decides what to do with it.
            last = reply(MessageType.RESULT, attempt.ref, null, new ResultPayload(partial, timing));
            if (behaviour == FaultInjector.Behaviour.DUPLICATE) {
                duplicate = reply(MessageType.RESULT, attempt.ref, null, new ResultPayload(partial, timing));
            }
        } catch (TaskCancelledException e) {
            last = cancelled(attempt, e.reason());
        } catch (DistriComputeException e) {
            failed.incrementAndGet();
            LOG.info("Attempt {} failed: {}", attempt.ref.attemptId(), e.getMessage());
            ErrorInfo error = e.toErrorInfo(TaskStatus.RUNNING);
            ErrorInfo located = new ErrorInfo(error.code(), error.message(), error.retryable(), error.status(),
                    attempt.partitionIndex, error.violations());
            last = reply(MessageType.ERROR, attempt.ref, null, new ErrorPayload(ProtocolErrorCode.TASK_FAILED,
                    e.getMessage(), error.retryable(), false, located));
        } catch (RuntimeException | Error e) {
            failed.incrementAndGet();
            LOG.error("Attempt {} failed unexpectedly", attempt.ref.attemptId(), e);
            String message = "Unexpected " + e.getClass().getSimpleName() + " in worker " + config.workerId()
                    + " (details in the worker's log)";
            last = reply(MessageType.ERROR, attempt.ref, null, new ErrorPayload(ProtocolErrorCode.TASK_FAILED,
                    message, true, false, new ErrorInfo(ErrorCode.EXECUTION_FAILED, message, true,
                            TaskStatus.RUNNING, attempt.partitionIndex, List.of())));
        }
        ScheduledFuture<?> timeout = attempt.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        synchronized (finished) {
            finished.put(attempt.ref.attemptId(), Boolean.TRUE);
        }
        // Free the slot before the final message leaves: the coordinator may reuse it as soon as it reads the
        // message. The attempt stays in the heartbeat's list until its final message is queued, because messages
        // on a connection are ordered.
        active.decrementAndGet();
        try {
            if (last != null) {
                attempt.outbox.send(last);
            }
            if (duplicate != null) {
                attempt.outbox.send(duplicate);
            }
        } finally {
            running.remove(attempt.ref.attemptId());
        }
    }

    /** Simulated hang: holds the slot and answers nothing, not even CANCEL, until the connection is gone. */
    private void hang(Running attempt) {
        LOG.warn("Fault simulation: attempt {} hangs", attempt.ref.attemptId());
        while (attempt.outbox.isOpen() && accepting) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        ScheduledFuture<?> timeout = attempt.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }
        active.decrementAndGet();
        running.remove(attempt.ref.attemptId());
    }

    /** Waits, honouring cancellation, for simulated slowness. */
    private static void pause(long nanos, Running attempt) {
        long deadline = System.nanoTime() + nanos;
        while (true) {
            attempt.cancellation.throwIfCancelled();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.max(1, Math.min(20, left / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TaskCancelledException(CancellationReason.SHUTDOWN);
            }
        }
    }

    private static PartialResultMessage withComputeNanos(PartialResultMessage p, long computeNanos) {
        return new PartialResultMessage(p.schemaVersion(), p.taskId(), p.partitionId(), p.index(), p.attempt(),
                p.resultType(), p.payload(), computeNanos, p.workerId());
    }

    /** Simulated corruption in transit: one base64 character changes, so the checksum no longer matches. */
    private static PartialResultMessage corrupted(PartialResultMessage p) {
        EncodedPayload e = p.payload();
        char[] chars = e.base64().toCharArray();
        int i = Math.min(12, chars.length - 1);
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        return new PartialResultMessage(p.schemaVersion(), p.taskId(), p.partitionId(), p.index(), p.attempt(),
                p.resultType(), new EncodedPayload(e.format(), e.sizeBytes(), e.crc32c(), new String(chars)),
                p.computeNanos(), p.workerId());
    }

    private Envelope cancelled(Running attempt, CancellationReason reason) {
        switch (reason) {
            case TIMEOUT -> {
                failed.incrementAndGet();
                String message = "Attempt " + attempt.ref.attemptId() + " exceeded its timeout on worker "
                        + config.workerId();
                return reply(MessageType.ERROR, attempt.ref, null, new ErrorPayload(ProtocolErrorCode.TASK_FAILED,
                        message, true, false, new ErrorInfo(ErrorCode.TIMEOUT, message, true, TaskStatus.RUNNING,
                                attempt.partitionIndex, List.of())));
            }
            case SHUTDOWN -> {
                return attempt.outbox.isOpen() ? reply(MessageType.ERROR, attempt.ref, null, new ErrorPayload(
                        ProtocolErrorCode.TASK_CANCELLED, "Worker " + config.workerId() + " is stopping", true,
                        false, null)) : null;
            }
            default -> {
                return reply(MessageType.ERROR, attempt.ref, null, new ErrorPayload(ProtocolErrorCode.TASK_CANCELLED,
                        "Cancelled at the coordinator's request", false, false, null));
            }
        }
    }

    private Envelope reply(MessageType type, AttemptRef ref, String inReplyTo, Payload payload) {
        return Envelope.builder(type).workerId(config.workerId()).attempt(ref).inReplyTo(inReplyTo)
                .payload(payload);
    }

    /** Forwards Core kernel progress as throttled {@code TASK_PROGRESS} messages. */
    private final class ThrottledProgress implements ProgressReporter {
        private final Running attempt;
        private final AtomicLong done = new AtomicLong();
        private volatile long total;
        private volatile String unitName;
        private volatile long lastSentNanos = System.nanoTime();

        ThrottledProgress(Running attempt) {
            this.attempt = attempt;
        }

        @Override
        public void start(long totalUnits, String unit) {
            this.total = Math.max(0, totalUnits);
            this.unitName = unit;
        }

        @Override
        public void advance(long units) {
            long value = done.addAndGet(Math.max(0, units));
            long now = System.nanoTime();
            if (now - lastSentNanos >= config.progressInterval().toNanos()) {
                lastSentNanos = now;
                String unit = unitName == null ? null : unitName.length() > 32 ? unitName.substring(0, 32)
                        : unitName;
                attempt.outbox.send(reply(MessageType.TASK_PROGRESS, attempt.ref, null,
                        new TaskProgressPayload(value, total, unit)));
            }
        }
    }
}
