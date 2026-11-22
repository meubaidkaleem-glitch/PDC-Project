package org.districompute.distributed.coordinator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.PartialResultCollector;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.metrics.MetricsCalculator;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.CancelPayload;
import org.districompute.distributed.protocol.CancelReason;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.Ids;
import org.districompute.distributed.protocol.MessageCodec;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.ResultPayload;
import org.districompute.distributed.protocol.TaskPayload;
import org.districompute.distributed.protocol.TaskProgressPayload;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.AttemptOutcome;
import org.districompute.distributed.registry.PerformanceStats;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.scheduler.Candidate;
import org.districompute.distributed.scheduler.Eligibility;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.scheduler.Scheduler;
import org.districompute.distributed.scheduler.SchedulingRequest;
import org.districompute.distributed.scheduler.TaskRequirements;
import org.districompute.distributed.task.Attempt;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.DistributedTask;
import org.districompute.distributed.task.PartitionState;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed tasks on the control loop: planning through Core, the bounded pending queue, scheduling, dispatch,
 * attempt messages, retries, completion and cancellation ({@code docs/scheduling.md},
 * {@code docs/fault-tolerance.md}).
 *
 * <p>Heavy Core work runs on small executors and posts its outcome back: opening a session (validation, planning,
 * input generation), encoding work units, and final aggregation and verification.
 */
final class TaskManager implements WorkerManager.AttemptMessages, WorkerManager.Listener, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskManager.class);
    private static final TaskPriority[] PRIORITY_ORDER = {TaskPriority.HIGH, TaskPriority.NORMAL, TaskPriority.LOW};

    private record Pending(DistributedTask task, PartitionState partition) {
    }

    private final CoordinatorConfig config;
    private final TaskDefaults defaults;
    private final ControlLoop loop;
    private final WorkerManager workers;
    private final Scheduler scheduler;
    private final MetricsCalculator metrics;
    private final ExecutorService planning;
    private final ExecutorService encoding;
    private final ExecutorService aggregation;
    private final Map<String, DistributedTask> active = new LinkedHashMap<>();
    private final Map<String, DistributedTask> finished;
    private final Map<String, DistributedTask> attemptIndex = new HashMap<>();
    private final Map<TaskPriority, ArrayDeque<Pending>> pending = new EnumMap<>(TaskPriority.class);
    private boolean scheduleRequested;

    TaskManager(CoordinatorConfig config, ControlLoop loop, WorkerManager workers, Scheduler scheduler) {
        this.config = config;
        this.defaults = config.tasks();
        this.loop = loop;
        this.workers = workers;
        this.scheduler = scheduler;
        this.metrics = new MetricsCalculator(id -> workers.registry().get(id).map(WorkerRecord::maxConcurrentTasks)
                .orElse(1));
        this.planning = executor("coordinator-planning", 2);
        this.encoding = executor("coordinator-encoding", 2);
        this.aggregation = executor("coordinator-aggregation", 2);
        for (TaskPriority priority : PRIORITY_ORDER) {
            pending.put(priority, new ArrayDeque<>());
        }
        int retained = defaults.retainedTasks();
        this.finished = new LinkedHashMap<>(64, 0.75f, false) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, DistributedTask> eldest) {
                if (size() > retained) {
                    eldest.getValue().attempts().forEach(a -> attemptIndex.remove(a.ref().attemptId()));
                    return true;
                }
                return false;
            }
        };
    }

    // ---------------------------------------------------------------- submission and planning (control loop)

    DistributedTask submit(TaskSpec spec, SubmitOptions options) {
        if (active.containsKey(spec.taskId()) || finished.containsKey(spec.taskId())) {
            throw new IllegalArgumentException("Task id " + spec.taskId() + " is already in use");
        }
        if (spec.constraints().mode() != ExecutionMode.DISTRIBUTED_CPU) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE, "The distributed runtime "
                    + "executes DISTRIBUTED_CPU tasks here; " + spec.constraints().mode() + " is not accepted");
        }
        if (active.size() >= defaults.maxActiveTasks()) {
            throw new DistriComputeException(ErrorCode.RESOURCE_EXHAUSTED, "The coordinator already runs "
                    + active.size() + " tasks (limit " + defaults.maxActiveTasks() + "); retry later");
        }
        PolicyName policy = options.policy() != null ? options.policy() : scheduler.defaultPolicy();
        scheduler.requireAvailable(policy);
        AlgorithmRef resolved = config.registry().resolve(spec.algorithm()).descriptor().ref();
        int maxAttempts = options.maxAttempts() != null ? options.maxAttempts() : defaults.maxAttempts();
        long attemptTimeout = (options.attemptTimeout() != null ? options.attemptTimeout()
                : defaults.attemptTimeout()).toMillis();
        DistributedTask task = new DistributedTask(spec, policy, maxAttempts, attemptTimeout, options.maxWorkers(),
                config.clock());
        active.put(spec.taskId(), task);
        task.validating();
        int perSlot = options.partitionsPerSlot() != null ? options.partitionsPerSlot()
                : defaults.partitionsPerSlot();
        int defaultPartitions = Math.max(1, perSlot * capableSlots(resolved, options.maxWorkers()));
        LOG.info("Task {} ({}) submitted with policy {}; planning {} default partitions", spec.taskId(), resolved,
                policy, defaultPartitions);
        planning.execute(() -> open(task, defaultPartitions));
        return task;
    }

    private void open(DistributedTask task, int defaultPartitions) {
        long start = System.nanoTime();
        try {
            PartitionedTaskSession session = PartitionedTaskSession.open(config.registry(), task.spec(),
                    defaultPartitions);
            TaskRequirements requirements = requirements(session, task.spec().priority());
            long elapsed = System.nanoTime() - start;
            loop.execute(() -> planned(task, session, requirements, elapsed));
        } catch (DistriComputeException e) {
            loop.execute(() -> finishWithoutSession(task, TaskStatus.FAILED, e.toErrorInfo(TaskStatus.VALIDATING)));
        } catch (RuntimeException e) {
            LOG.error("Planning task {} failed unexpectedly", task.taskId(), e);
            loop.execute(() -> finishWithoutSession(task, TaskStatus.FAILED, new ErrorInfo(ErrorCode.INTERNAL_ERROR,
                    "Planning failed: " + e, false, TaskStatus.VALIDATING, null, List.of())));
        }
    }

    private void planned(DistributedTask task, PartitionedTaskSession session, TaskRequirements requirements,
            long openNanos) {
        if (task.isFinished()) {
            // Cancelled while Core was planning.
            session.cancel(CancellationReason.USER_REQUESTED);
            return;
        }
        task.planned(session, requirements, openNanos);
        if (pendingPartitions() + task.partitions().size() > defaults.maxPendingPartitions()) {
            failTask(task, new ErrorInfo(ErrorCode.RESOURCE_EXHAUSTED, task.partitions().size()
                    + " partitions do not fit in the coordinator's pending queue (" + pendingPartitions() + " of "
                    + defaults.maxPendingPartitions() + " in use)", true, TaskStatus.QUEUED, null, List.of()));
            return;
        }
        task.partitions().forEach(p -> enqueue(task, p));
        LOG.info("Task {} planned: {} partitions in {} ms", task.taskId(), task.partitions().size(),
                openNanos / 1_000_000);
        requestSchedule();
    }

    private TaskRequirements requirements(PartitionedTaskSession session, TaskPriority priority) {
        WorkloadProfile profile = session.profile();
        int n = Math.max(1, session.partitions().size());
        long payload = (long) (profile.inputBytes() / (double) n * 4 / 3) + 4096;
        return new TaskRequirements(profile.algorithm(), profile.estimatedOperations() / n, payload,
                profile.estimatedPeakMemoryBytes() / n, priority);
    }

    private int capableSlots(AlgorithmRef algorithm, Integer maxWorkers) {
        TaskRequirements probe = new TaskRequirements(algorithm, 0, 0, 0, TaskPriority.NORMAL);
        List<Integer> slots = workers.registry().connected().stream()
                .filter(w -> w.status() == WorkerStatus.ACTIVE)
                .filter(w -> Eligibility.incapable(w.capabilities(), probe).isEmpty())
                .map(WorkerRecord::maxConcurrentTasks).sorted(Comparator.reverseOrder()).toList();
        int count = maxWorkers == null ? slots.size() : Math.min(maxWorkers, slots.size());
        return slots.stream().limit(count).mapToInt(Integer::intValue).sum();
    }

    // ---------------------------------------------------------------- scheduling and dispatch (control loop)

    private void enqueue(DistributedTask task, PartitionState partition) {
        if (partition.queued()) {
            return;
        }
        partition.setQueued(true);
        partition.setStatus(PartitionStatus.PENDING);
        pending.get(task.spec().priority()).addLast(new Pending(task, partition));
    }

    void requestSchedule() {
        if (!scheduleRequested) {
            scheduleRequested = true;
            loop.execute(this::schedule);
        }
    }

    private void schedule() {
        scheduleRequested = false;
        Set<String> blocked = new HashSet<>();
        for (TaskPriority priority : PRIORITY_ORDER) {
            Iterator<Pending> it = pending.get(priority).iterator();
            while (it.hasNext()) {
                if (workers.registry().connected().stream().noneMatch(w -> w.status() == WorkerStatus.ACTIVE
                        && w.freeSlots() > 0)) {
                    return;
                }
                Pending entry = it.next();
                DistributedTask task = entry.task();
                if (task.isFinished() || task.isCompleting()
                        || entry.partition().status() == PartitionStatus.COMPLETED) {
                    dequeue(it, entry);
                    continue;
                }
                if (blocked.contains(task.taskId())) {
                    continue;
                }
                long start = System.nanoTime();
                PartitionState partition = entry.partition();
                int attemptNumber = partition.nextAttemptNumber();
                String attemptId = Ids.attemptId(partition.descriptor().partitionId(), attemptNumber);
                SchedulingRequest request = new SchedulingRequest(task.taskId(), partition.descriptor().partitionId(),
                        attemptNumber, task.requirements(), partition.excludedWorkers(), task.allowedWorkers());
                Scheduler.Decision decision = scheduler.decide(request, attemptId, candidates(task),
                        this::capabilities, task.policy(), config.clock().instant());
                if (decision instanceof Scheduler.Assign assign) {
                    dequeue(it, entry);
                    dispatch(task, partition, attemptNumber, assign);
                    task.addSchedulingNanos(System.nanoTime() - start);
                } else {
                    task.addSchedulingNanos(System.nanoTime() - start);
                    blocked.add(task.taskId());
                }
            }
        }
    }

    private void dequeue(Iterator<Pending> it, Pending entry) {
        it.remove();
        entry.partition().setQueued(false);
    }

    private List<Candidate> candidates(DistributedTask task) {
        long now = System.nanoTime();
        List<Candidate> list = new ArrayList<>();
        for (WorkerRecord w : workers.registry().connected()) {
            Optional<PerformanceStats> perf = task.requirements() == null ? Optional.empty()
                    : w.history().stats(task.requirements().algorithm().id());
            WorkerCapabilities caps = w.capabilities();
            int assigned = w.assignedAttempts().size();
            list.add(new Candidate(w.workerId(), w.status(), w.maxConcurrentTasks(), assigned, caps.cpuCores(),
                    caps.maxTaskMemoryBytes(), caps.maxMessageBytes(), w.load().freeMemoryBytes(),
                    perf.map(PerformanceStats::operationsPerSecond).orElse(0.0),
                    perf.map(PerformanceStats::transferBytesPerSecond).orElse(0.0),
                    perf.map(PerformanceStats::samples).orElse(0L), assigned == 0 ? now - w.idleSinceNanos() : 0,
                    w.lastAssignedNanos()));
        }
        list.sort(Comparator.comparing(Candidate::workerId));
        return list;
    }

    private WorkerCapabilities capabilities(String workerId) {
        return workers.registry().get(workerId).map(WorkerRecord::capabilities).orElseThrow();
    }

    private void dispatch(DistributedTask task, PartitionState partition, int number, Scheduler.Assign assign) {
        String workerId = assign.record().workerId();
        WorkerRecord worker = workers.registry().get(workerId).orElseThrow();
        WorkerSession session = workers.session(workerId).orElseThrow();
        String partitionId = partition.descriptor().partitionId();
        AttemptRef ref = new AttemptRef(task.spec().experimentId(), task.taskId(), partitionId,
                Ids.attemptId(partitionId, number));
        Attempt attempt = new Attempt(ref, number, workerId, assign.record(), System.nanoTime());
        task.addAttempt(partition, attempt);
        attemptIndex.put(ref.attemptId(), task);
        worker.assign(ref.attemptId(), attempt.decidedNanos());
        LOG.debug("{} -> {}: {}", ref.attemptId(), workerId, assign.record().explanation());
        long timeout = task.attemptTimeoutMillis();
        TaskPriority priority = task.spec().priority();
        encoding.execute(() -> encodeAndSend(task, attempt, session, partition.index(), timeout, priority));
    }

    private void encodeAndSend(DistributedTask task, Attempt attempt, WorkerSession session, int index,
            long timeoutMillis, TaskPriority priority) {
        long start = System.nanoTime();
        try {
            WorkUnitMessage unit = task.session().workUnit(index, attempt.number());
            byte[] frame = MessageCodec.encode(Envelope.builder(MessageType.TASK).workerId(attempt.workerId())
                    .attempt(attempt.ref()).payload(new TaskPayload(unit, timeoutMillis, priority)));
            long serialization = System.nanoTime() - start;
            boolean queued = session.sendFrame(frame);
            long sent = System.nanoTime();
            Instant at = config.clock().instant();
            loop.execute(() -> sent(task, attempt, queued, at, sent, serialization, frame.length));
        } catch (IllegalArgumentException e) {
            loop.execute(() -> dispatchFailed(task, attempt, "the work unit cannot be sent to " + attempt.workerId()
                    + ": " + e.getMessage()));
        } catch (IllegalStateException e) {
            // The task ended while the work unit was being prepared.
            loop.execute(() -> dispatchFailed(task, attempt, "task ended before dispatch"));
        } catch (RuntimeException e) {
            LOG.error("Encoding {} failed", attempt.ref().attemptId(), e);
            loop.execute(() -> dispatchFailed(task, attempt, "encoding failed: " + e));
        }
    }

    private void sent(DistributedTask task, Attempt attempt, boolean queued, Instant at, long nanos,
            long serialization, long bytes) {
        if (!queued) {
            dispatchFailed(task, attempt, "the connection to " + attempt.workerId() + " is closed");
            return;
        }
        attempt.sent(at, nanos, serialization, bytes);
    }

    private void dispatchFailed(DistributedTask task, Attempt attempt, String reason) {
        if (attempt.status().isTerminal()) {
            return;
        }
        releaseSlot(attempt);
        attempt.finish(AttemptStatus.FAILED, config.clock().instant(), reason, true);
        PartitionState partition = partitionOf(task, attempt);
        partition.countRejection(attempt.workerId(), true);
        LOG.warn("Dispatch of {} failed: {}", attempt.ref().attemptId(), reason);
        retryOrFail(task, partition, attempt, true, false, ErrorCode.EXECUTION_FAILED, reason);
    }

    // ---------------------------------------------------------------- attempt messages (control loop)

    @Override
    public void onAttemptMessage(WorkerSession session, WorkerRecord worker, Envelope message, long receivedNanos,
            int frameBytes) {
        DistributedTask task = attemptIndex.get(message.attemptId());
        Attempt attempt = task == null ? null : task.attempt(message.attemptId());
        if (attempt == null) {
            if (message.type() != MessageType.ERROR) {
                session.send(session.error(ProtocolErrorCode.UNKNOWN_TASK, "No attempt " + message.attemptId()
                        + " is known to this coordinator", message.messageId(), message.attemptRef(), false, false));
            }
            return;
        }
        if (!attempt.workerId().equals(worker.workerId())) {
            worker.count(AttemptOutcome.INVALID_RESULT);
            session.protocolError(ProtocolErrorCode.INVALID_MESSAGE, "Attempt " + attempt.ref().attemptId()
                    + " was assigned to " + attempt.workerId() + ", not to " + worker.workerId(),
                    message.messageId(), message.attemptRef(), false);
            return;
        }
        switch (message.type()) {
            case TASK_ACCEPTED -> attempt.accepted(config.clock().instant());
            case TASK_PROGRESS -> {
                TaskProgressPayload progress = message.payload(TaskProgressPayload.class);
                attempt.progress(progress.completedUnits(), progress.totalUnits(), progress.unit());
            }
            case RESULT -> result(task, attempt, worker, message.payload(ResultPayload.class), receivedNanos,
                    frameBytes);
            case ERROR -> attemptError(task, attempt, worker, message.payload(ErrorPayload.class));
            default -> LOG.warn("Unexpected {} for {}", message.type(), attempt.ref().attemptId());
        }
    }

    private void result(DistributedTask task, Attempt attempt, WorkerRecord worker, ResultPayload payload,
            long receivedNanos, int frameBytes) {
        attempt.resultReceived(receivedNanos, frameBytes + 1L, payload.timing(),
                payload.partialResult().computeNanos());
        long start = System.nanoTime();
        PartialResultCollector.Offer offer = task.session().accept(payload.partialResult());
        attempt.resultProcessed(System.nanoTime() - start);
        releaseSlot(attempt);
        PartitionState partition = partitionOf(task, attempt);
        Instant now = config.clock().instant();
        switch (offer.classification()) {
            case ACCEPTED -> {
                attempt.finish(AttemptStatus.SUCCEEDED, now, null, false);
                worker.count(AttemptOutcome.SUCCEEDED);
                worker.history().observe(task.requirements().algorithm().id(),
                        task.requirements().operationsPerPartition(), attempt.computeNanos(),
                        attempt.bytesSent() + attempt.bytesReceived(), attempt.transferNanos());
                task.partitionCompleted(partition, receivedNanos);
                for (Attempt other : partition.live()) {
                    other.finish(AttemptStatus.SUPERSEDED, now, "another attempt delivered first", false);
                    sendCancel(other, CancelReason.SUPERSEDED);
                }
                if (task.session().isComplete() && !task.isCompleting()) {
                    complete(task);
                }
            }
            case DUPLICATE -> {
                if (!attempt.status().isTerminal() || attempt.status() == AttemptStatus.SUPERSEDED) {
                    attempt.finish(AttemptStatus.SUPERSEDED, now, "identical to the accepted result", false);
                }
                worker.count(AttemptOutcome.DUPLICATE_RESULT);
            }
            case LATE -> {
                if (!attempt.status().isTerminal()) {
                    attempt.finish(AttemptStatus.SUPERSEDED, now, "the task had already ended", false);
                }
            }
            case CONFLICTING_DUPLICATE -> {
                attempt.finish(AttemptStatus.INVALID, now, "conflicts with the accepted result: " + offer.detail(),
                        false);
                worker.count(AttemptOutcome.INVALID_RESULT);
                LOG.error("Worker {} returned a result for {} that conflicts with the accepted one: {}",
                        worker.workerId(), attempt.ref().attemptId(), offer.detail());
            }
            case INVALID, UNKNOWN_PARTITION -> {
                attempt.finish(AttemptStatus.INVALID, now, "invalid result: " + offer.detail(), true);
                worker.count(AttemptOutcome.INVALID_RESULT);
                LOG.warn("Worker {} returned an invalid result for {}: {}", worker.workerId(),
                        attempt.ref().attemptId(), offer.detail());
                if (partition.status() != PartitionStatus.COMPLETED) {
                    task.session().markFailed(partition.index(), new ErrorInfo(ErrorCode.INVALID_PARTIAL_RESULT,
                            offer.detail(), true, TaskStatus.RUNNING, partition.index(), List.of()));
                    retryOrFail(task, partition, attempt, true, true, ErrorCode.INVALID_PARTIAL_RESULT,
                            "invalid result from " + worker.workerId() + ": " + offer.detail());
                }
            }
            default -> throw new IllegalStateException("Unknown classification " + offer.classification());
        }
        requestSchedule();
    }

    private void attemptError(DistributedTask task, Attempt attempt, WorkerRecord worker, ErrorPayload error) {
        PartitionState partition = partitionOf(task, attempt);
        Instant now = config.clock().instant();
        boolean wasLive = !attempt.status().isTerminal();
        releaseSlot(attempt);
        switch (error.code()) {
            case TASK_REJECTED -> {
                if (!wasLive) {
                    break;
                }
                attempt.finish(AttemptStatus.REJECTED, now, error.message(), error.retryable());
                worker.count(AttemptOutcome.REJECTED);
                partition.countRejection(worker.workerId(), true);
                retryOrFail(task, partition, attempt, error.retryable(), !error.retryable(), ErrorCode.EXECUTION_FAILED,
                        "rejected by " + worker.workerId() + ": " + error.message());
            }
            case TASK_FAILED -> {
                if (!wasLive) {
                    break;
                }
                ErrorInfo coreError = error.taskError() != null ? error.taskError()
                        : new ErrorInfo(ErrorCode.EXECUTION_FAILED, error.message(), error.retryable(),
                                TaskStatus.RUNNING, partition.index(), List.of());
                boolean timeout = coreError.code() == ErrorCode.TIMEOUT;
                attempt.finish(timeout ? AttemptStatus.TIMED_OUT : AttemptStatus.FAILED, now, error.message(),
                        error.retryable());
                worker.count(timeout ? AttemptOutcome.TIMED_OUT : AttemptOutcome.FAILED);
                task.session().markFailed(partition.index(), coreError);
                retryOrFail(task, partition, attempt, error.retryable(), true, coreError.code(),
                        "failed on " + worker.workerId() + ": " + error.message());
            }
            case TASK_CANCELLED, UNKNOWN_TASK -> {
                if (!wasLive) {
                    break;
                }
                // Not requested by the coordinator: the worker is stopping or lost the attempt.
                attempt.finish(AttemptStatus.LOST, now, error.message(), true);
                worker.count(AttemptOutcome.LOST);
                retryOrFail(task, partition, attempt, true, false, ErrorCode.EXECUTION_FAILED,
                        "lost on " + worker.workerId() + ": " + error.message());
            }
            default -> LOG.warn("Worker {} reported {} for {}: {}", worker.workerId(), error.code(),
                    attempt.ref().attemptId(), error.message());
        }
        requestSchedule();
    }

    /**
     * Decides what happens to a partition after a failed attempt: nothing while another attempt is live, a new
     * attempt if the failure and the partition allow it and budgets remain, otherwise the task fails.
     */
    private void retryOrFail(DistributedTask task, PartitionState partition, Attempt failed, boolean retryable,
            boolean countFailure, ErrorCode code, String reason) {
        if (task.isFinished() || task.isCompleting() || partition.status() == PartitionStatus.COMPLETED) {
            return;
        }
        if (countFailure) {
            partition.countFailure(failed.workerId());
        }
        if (!partition.live().isEmpty() || partition.queued()) {
            return;
        }
        boolean budgetLeft = partition.countedFailures() < task.maxAttempts()
                && partition.rejections() < defaults.maxRejectionsPerPartition();
        if (retryable && task.session().retryEligible(partition.index()) && budgetLeft) {
            LOG.info("Retrying {} ({}): {}", partition.descriptor().partitionId(),
                    partition.countedFailures() + " counted failures", reason);
            enqueue(task, partition);
            requestSchedule();
            return;
        }
        String why = !retryable ? "the failure is not retryable"
                : !task.session().retryEligible(partition.index()) ? "the partition is not retry-safe"
                : "the retry budget is exhausted (" + partition.countedFailures() + " failures, "
                        + partition.rejections() + " refusals)";
        failTask(task, new ErrorInfo(code, "Partition " + partition.descriptor().partitionId() + " failed: " + reason
                + "; " + why, false, TaskStatus.RUNNING, partition.index(), List.of()));
    }

    // ---------------------------------------------------------------- workers (control loop)

    @Override
    public void workerRegistered(WorkerRecord worker, boolean reconnection) {
        requestSchedule();
    }

    @Override
    public void workerStatusChanged(WorkerRecord worker, WorkerStatus previous, String reason) {
        requestSchedule();
    }

    @Override
    public void workerDisconnected(WorkerRecord worker, List<String> assignedAttempts, String reason) {
        Instant now = config.clock().instant();
        for (String attemptId : assignedAttempts) {
            DistributedTask task = attemptIndex.get(attemptId);
            Attempt attempt = task == null ? null : task.attempt(attemptId);
            if (attempt == null) {
                continue;
            }
            attempt.releaseSlot();
            if (attempt.status().isTerminal()) {
                continue;
            }
            attempt.finish(AttemptStatus.LOST, now, "worker " + worker.workerId() + " lost: " + reason, true);
            worker.count(AttemptOutcome.LOST);
            retryOrFail(task, partitionOf(task, attempt), attempt, true, true, ErrorCode.EXECUTION_FAILED,
                    "worker " + worker.workerId() + " was lost (" + reason + ")");
        }
        requestSchedule();
    }

    // ---------------------------------------------------------------- completion and cancellation (control loop)

    private void complete(DistributedTask task) {
        task.startCompleting();
        aggregation.execute(() -> {
            long start = System.nanoTime();
            TaskResult result;
            try {
                result = task.session().complete();
            } catch (DistriComputeException e) {
                result = task.session().fail(e.toErrorInfo(TaskStatus.AGGREGATING));
            } catch (RuntimeException e) {
                LOG.error("Aggregating task {} failed unexpectedly", task.taskId(), e);
                result = task.session().fail(new ErrorInfo(ErrorCode.AGGREGATION_FAILED, "Aggregation failed: " + e,
                        false, TaskStatus.AGGREGATING, null, List.of()));
            }
            TaskResult done = result;
            long elapsed = System.nanoTime() - start;
            loop.execute(() -> {
                task.setAggregationNanos(elapsed);
                finish(task, done);
            });
        });
    }

    private void failTask(DistributedTask task, ErrorInfo error) {
        if (task.isFinished() || task.isCompleting()) {
            return;
        }
        LOG.warn("Task {} failed: {}", task.taskId(), error.message());
        if (task.session() == null) {
            finishWithoutSession(task, TaskStatus.FAILED, error);
            return;
        }
        finish(task, task.session().fail(error));
    }

    boolean cancel(String taskId, String reason) {
        DistributedTask task = active.get(taskId);
        if (task == null || task.isFinished() || task.isCompleting()) {
            return false;
        }
        LOG.info("Task {} cancelled: {}", taskId, reason);
        if (task.session() == null) {
            finishWithoutSession(task, TaskStatus.CANCELLED, new ErrorInfo(ErrorCode.CANCELLED, reason, false,
                    TaskStatus.VALIDATING, null, List.of()));
        } else {
            finish(task, task.session().cancel(CancellationReason.USER_REQUESTED));
        }
        return true;
    }

    private void finishWithoutSession(DistributedTask task, TaskStatus status, ErrorInfo error) {
        if (task.isFinished()) {
            return;
        }
        finish(task, task.resultWithoutSession(status, error));
    }

    private void finish(DistributedTask task, TaskResult result) {
        Instant now = config.clock().instant();
        CancelReason reason = result.status() == TaskStatus.CANCELLED ? CancelReason.USER_REQUESTED
                : CancelReason.TASK_FINISHED;
        for (Attempt attempt : task.attempts()) {
            if (!attempt.status().isTerminal()) {
                attempt.finish(AttemptStatus.CANCELLED, now, "task " + result.status(), false);
                sendCancel(attempt, reason);
            }
        }
        task.finish(result, now, metrics);
        active.remove(task.taskId());
        finished.put(task.taskId(), task);
        LOG.info("Task {} {} ({} attempts)", task.taskId(), result.status(), task.attempts().size());
        requestSchedule();
    }

    private void sendCancel(Attempt attempt, CancelReason reason) {
        workers.session(attempt.workerId()).ifPresent(session -> session.send(session.message(MessageType.CANCEL,
                attempt.ref(), null, new CancelPayload(reason, null))));
    }

    private void releaseSlot(Attempt attempt) {
        if (attempt.releaseSlot()) {
            workers.registry().get(attempt.workerId())
                    .ifPresent(w -> w.release(attempt.ref().attemptId(), System.nanoTime()));
        }
    }

    private static PartitionState partitionOf(DistributedTask task, Attempt attempt) {
        String partitionId = attempt.ref().partitionId();
        int index = Integer.parseInt(partitionId.substring(partitionId.lastIndexOf("/p") + 2));
        return task.partitions().get(index);
    }

    // ---------------------------------------------------------------- queries and shutdown

    Optional<TaskSnapshot> snapshot(String taskId) {
        DistributedTask task = active.containsKey(taskId) ? active.get(taskId) : finished.get(taskId);
        return Optional.ofNullable(task).map(t -> t.snapshot(metrics));
    }

    List<TaskSnapshot> snapshots() {
        List<TaskSnapshot> list = new ArrayList<>();
        finished.values().forEach(t -> list.add(t.snapshot(metrics)));
        active.values().forEach(t -> list.add(t.snapshot(metrics)));
        return list;
    }

    Optional<DistributedTask> task(String taskId) {
        return Optional.ofNullable(active.containsKey(taskId) ? active.get(taskId) : finished.get(taskId));
    }

    int pendingPartitions() {
        return pending.values().stream().mapToInt(ArrayDeque::size).sum();
    }

    /** Cancels every active task because the coordinator stops (control loop). */
    void shutdown() {
        for (DistributedTask task : new ArrayList<>(active.values())) {
            if (task.isCompleting()) {
                continue;
            }
            if (task.session() == null) {
                finishWithoutSession(task, TaskStatus.CANCELLED, new ErrorInfo(ErrorCode.CANCELLED,
                        "The coordinator is shutting down", true, TaskStatus.VALIDATING, null, List.of()));
            } else {
                finish(task, task.session().cancel(CancellationReason.SHUTDOWN));
            }
        }
    }

    @Override
    public void close() {
        planning.shutdownNow();
        encoding.shutdownNow();
        aggregation.shutdownNow();
    }

    private static ExecutorService executor(String name, int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> Thread.ofPlatform()
                .name(name + "-" + counter.incrementAndGet()).daemon(true).unstarted(runnable));
    }
}
