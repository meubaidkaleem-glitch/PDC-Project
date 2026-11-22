package org.districompute.core.execution;

/**
 * The outcome and timing of one unit of work executed by a {@link WorkerPool}.
 *
 * @param index position of the work item in the submitted list
 * @param value value returned by the work item
 * @param submittedNanos {@link System#nanoTime()} when it was handed to the pool
 * @param startedNanos when a worker thread started it
 * @param finishedNanos when it returned
 * @param worker name of the worker thread that ran it
 * @param allocatedBytes heap bytes the worker allocated while running it, or {@code -1} if unavailable
 * @param <T> value type
 */
public record TaskRun<T>(int index, T value, long submittedNanos, long startedNanos, long finishedNanos,
        String worker, long allocatedBytes) {

    /**
     * Time spent running on a worker thread.
     *
     * @return {@code finishedNanos - startedNanos}
     */
    public long busyNanos() {
        return finishedNanos - startedNanos;
    }

    /**
     * Time between submission and start (queueing and dispatch).
     *
     * @return {@code startedNanos - submittedNanos}
     */
    public long queueNanos() {
        return startedNanos - submittedNanos;
    }
}
