package org.districompute.core.execution;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.metrics.ResourceUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded pool of worker threads for CPU-bound work.
 *
 * <ul>
 *   <li><b>Bounded.</b> A fixed number of threads is created and started up front (so thread start-up never shows
 *       up in measurements). The pool never grows. The work queue is bounded, and overflow is rejected, never run
 *       on the caller's thread.</li>
 *   <li><b>Controlled submission.</b> {@link #runAll} keeps at most {@code parallelism} items of one batch in flight
 *       and submits the next item only when one completes.</li>
 *   <li><b>Thread-safe collection.</b> Completed items arrive through an {@link ExecutorCompletionService} (a
 *       blocking queue) and are handed to the caller's callback on the calling thread, one at a time.</li>
 *   <li><b>Fail fast.</b> The first failure cancels the batch's cancellation source with
 *       {@link CancellationReason#FAILURE}, so running siblings stop at their next check and unsubmitted items are
 *       abandoned. The original failure is then rethrown.</li>
 *   <li><b>Structured.</b> {@code runAll} returns or throws only after every submitted item has finished, so no
 *       work outlives the call.</li>
 * </ul>
 */
public final class WorkerPool implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerPool.class);
    private static final long POLL_MILLIS = 20;
    private static final long SHUTDOWN_SECONDS = 30;

    private final int threads;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates and starts the pool.
     *
     * @param threads number of worker threads, at least 1
     * @param maxConcurrentBatches how many {@link #runAll} batches may run at the same time; sizes the work queue
     * @param threadNamePrefix prefix of worker thread names
     */
    public WorkerPool(int threads, int maxConcurrentBatches, String threadNamePrefix) {
        if (threads < 1 || maxConcurrentBatches < 1) {
            throw new IllegalArgumentException("threads and maxConcurrentBatches must be at least 1");
        }
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix");
        this.threads = threads;
        AtomicInteger counter = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(threads * maxConcurrentBatches), runnable -> {
                    Thread thread = new Thread(runnable, threadNamePrefix + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
    }

    /**
     * Number of worker threads.
     *
     * @return the fixed thread count
     */
    public int threads() {
        return threads;
    }

    /**
     * Largest number of threads that ever existed in this pool, for verifying boundedness.
     *
     * @return the peak thread count
     */
    public int largestPoolSize() {
        return executor.getLargestPoolSize();
    }

    /**
     * Whether the pool has shut down and all its threads have ended.
     *
     * @return {@code true} once terminated
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Runs a batch of work items with at most {@code parallelism} in flight.
     *
     * @param tasks work items; each runs once at most
     * @param parallelism maximum items in flight; clamped to {@code [1, threads()]}
     * @param scope cancellation source of the batch. Items observe it through their own context. It is cancelled
     *     with {@link CancellationReason#FAILURE} on the first failure.
     * @param onCompletion called on the calling thread for each successful item, in completion order. If it throws,
     *     the batch fails with that exception.
     * @param <T> item result type
     * @return the runs in submission (index) order
     * @throws TaskCancelledException if the scope was cancelled and no item failed
     * @throws RuntimeException the first item failure (unwrapped), or a {@link DistriComputeException} with
     *     {@link ErrorCode#EXECUTION_FAILED} for a checked exception
     */
    public <T> List<TaskRun<T>> runAll(List<? extends Callable<? extends T>> tasks, int parallelism,
            CancellationSource scope, Consumer<? super TaskRun<T>> onCompletion) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(onCompletion, "onCompletion");
        if (closed.get()) {
            throw new IllegalStateException("The worker pool is closed");
        }
        int total = tasks.size();
        int limit = Math.max(1, Math.min(parallelism, threads));
        CompletionService<TaskRun<T>> completions = new ExecutorCompletionService<>(executor);
        List<TaskRun<T>> runs = new ArrayList<>(Collections.nCopies(total, null));
        int next = 0;
        int inFlight = 0;
        Throwable failure = null;
        boolean interrupted = false;
        while (next < total || inFlight > 0) {
            if (failure != null || scope.isCancelled()) {
                next = total;
            }
            while (inFlight < limit && next < total) {
                completions.submit(instrument(next, tasks.get(next), scope));
                next++;
                inFlight++;
            }
            if (inFlight == 0) {
                break;
            }
            Future<TaskRun<T>> done;
            try {
                done = completions.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
                scope.cancel(CancellationReason.USER_REQUESTED);
                continue;
            }
            if (done == null) {
                continue;
            }
            inFlight--;
            try {
                TaskRun<T> run = resultOf(done);
                runs.set(run.index(), run);
                if (failure == null) {
                    onCompletion.accept(run);
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                boolean consequenceOfCancellation = cause instanceof TaskCancelledException && scope.isCancelled();
                if (failure == null && !consequenceOfCancellation) {
                    failure = cause;
                    scope.cancel(CancellationReason.FAILURE);
                }
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                    scope.cancel(CancellationReason.FAILURE);
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (failure != null) {
            throw asUnchecked(failure);
        }
        if (scope.isCancelled()) {
            throw new TaskCancelledException(scope.reason().orElse(CancellationReason.USER_REQUESTED));
        }
        return Collections.unmodifiableList(runs);
    }

    private static <T> Callable<TaskRun<T>> instrument(int index, Callable<? extends T> task,
            CancellationSource scope) {
        long submitted = System.nanoTime();
        return () -> {
            long started = System.nanoTime();
            scope.throwIfCancelled();
            long allocatedBefore = ResourceUsage.currentThreadAllocatedBytes();
            T value = task.call();
            long finished = System.nanoTime();
            long allocatedAfter = ResourceUsage.currentThreadAllocatedBytes();
            long allocated = allocatedBefore < 0 || allocatedAfter < 0 ? -1 : allocatedAfter - allocatedBefore;
            return new TaskRun<>(index, value, submitted, started, finished, Thread.currentThread().getName(),
                    allocated);
        };
    }

    /** Gets the result of a future that is already done, without being interruptible. */
    private static <T> T resultOf(Future<T> done) throws ExecutionException {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return done.get();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static RuntimeException asUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new DistriComputeException(ErrorCode.EXECUTION_FAILED, failure.toString(), failure);
    }

    /**
     * Shuts the pool down: no new batches are accepted, queued work finishes, and threads end. If threads are still
     * busy after 30 seconds they are interrupted.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                LOG.warn("Worker pool did not terminate within {} s; interrupting workers", SHUTDOWN_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
