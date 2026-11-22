package org.districompute.core.metrics;

/**
 * Measurements of one execution on one node (integration contract, section 10). Durations are wall-clock
 * nanoseconds from {@link System#nanoTime()}. A value of {@code -1} means "not available on this JVM".
 *
 * @param validationNanos parameter validation, semantic checks, workload analysis and memory guard
 * @param inputPreparationNanos generating or decoding the input; excluded from {@code executionNanos}
 * @param partitioningNanos building the partition plan and extracting work units
 * @param computeNanos from the first partition submission to the last partial result collected (sequential: the
 *     reference implementation)
 * @param aggregationNanos collecting, ordering and assembling partial results
 * @param executionNanos partitioning + compute + aggregation, measured as one interval; <b>the basis for
 *     speedup</b>
 * @param verificationNanos independent result verification; excluded from {@code executionNanos}
 * @param busyNanos sum of the time spent inside kernels, across all threads
 * @param schedulingOverheadNanos {@code max(0, computeNanos − busyNanos / threads)}: compute-phase time not
 *     explained by evenly divided kernel work (dispatch, queueing, load imbalance, idle threads)
 * @param threads threads used
 * @param partitions partitions executed (1 for sequential execution)
 * @param cpuNanos process CPU time during {@code executionNanos}, including JIT and GC threads, or {@code -1}
 * @param gcCount garbage collections during {@code executionNanos}, or {@code -1}
 * @param gcMillis accumulated collection time during {@code executionNanos} in milliseconds, or {@code -1}
 * @param allocatedBytes heap bytes allocated by the threads that ran the execution, or {@code -1}
 */
public record ExecutionMetrics(
        long validationNanos,
        long inputPreparationNanos,
        long partitioningNanos,
        long computeNanos,
        long aggregationNanos,
        long executionNanos,
        long verificationNanos,
        long busyNanos,
        long schedulingOverheadNanos,
        int threads,
        int partitions,
        long cpuNanos,
        long gcCount,
        long gcMillis,
        long allocatedBytes) {

    /** Metrics of an execution that stopped before any measurement. */
    public static final ExecutionMetrics NONE = new ExecutionMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, -1, -1);

    /**
     * Computes the scheduling overhead as defined above.
     *
     * @param computeNanos compute-phase wall time
     * @param busyNanos summed kernel time
     * @param threads threads used, at least 1
     * @return the overhead in nanoseconds, never negative
     */
    public static long schedulingOverhead(long computeNanos, long busyNanos, int threads) {
        return Math.max(0, computeNanos - busyNanos / Math.max(1, threads));
    }
}
