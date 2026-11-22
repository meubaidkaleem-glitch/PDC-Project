package org.districompute.distributed.metrics;

import java.util.List;

/**
 * Metrics of one distributed task ({@code docs/integration-contract.md}, "Distributed metrics"). Computation,
 * communication and scheduling are kept separate, as Core's contract requires; Core's own {@code ExecutionMetrics}
 * stay in the task result unchanged.
 *
 * <p>All durations are nanoseconds on one machine's clock: wall times on the coordinator, kernel and execution
 * times on the workers. Sums run over attempts, so with parallel workers they exceed wall time.
 *
 * @param totalNanos submission to the end of the task (coordinator wall time)
 * @param queueNanos submission to the first dispatch
 * @param planningNanos Core session opening: validation, workload analysis, partition plan, input preparation
 * @param distributedNanos first dispatch to the last accepted result (wall time; includes communication)
 * @param aggregationNanos Core aggregation and verification after the last result (wall time)
 * @param kernelNanos sum of kernel times of accepted attempts (Core {@code computeNanos} on the workers)
 * @param workerExecuteNanos sum of decode, kernel and encode times of all attempts that reported timings
 * @param transferNanos sum of transport times of accepted attempts (round trip minus worker residence)
 * @param serializationNanos sum of coordinator time building and encoding work units and frames
 * @param resultProcessingNanos sum of coordinator time decoding, validating and classifying results
 * @param schedulingNanos sum of coordinator time spent in scheduling decisions
 * @param bytesSent bytes of all {@code TASK} frames
 * @param bytesReceived bytes of all {@code RESULT} frames
 * @param partitions planned partitions
 * @param attempts attempts dispatched
 * @param succeededAttempts attempts whose result Core accepted
 * @param failedAttempts attempts that ended in failure, rejection, timeout, loss or an invalid result
 * @param retries attempts beyond the first per partition
 * @param workersUsed distinct workers that received attempts
 * @param workerUtilization {@code workerExecuteNanos / (distributedNanos × slots of the workers used)}, in [0, 1]
 * @param throughputPartitionsPerSecond partitions completed per second of {@code distributedNanos}
 * @param loadImbalance largest per-worker kernel time divided by the mean (1.0 = perfectly balanced)
 * @param perWorker load distribution
 */
public record DistributedMetrics(
        long totalNanos,
        long queueNanos,
        long planningNanos,
        long distributedNanos,
        long aggregationNanos,
        long kernelNanos,
        long workerExecuteNanos,
        long transferNanos,
        long serializationNanos,
        long resultProcessingNanos,
        long schedulingNanos,
        long bytesSent,
        long bytesReceived,
        int partitions,
        int attempts,
        int succeededAttempts,
        int failedAttempts,
        int retries,
        int workersUsed,
        double workerUtilization,
        double throughputPartitionsPerSecond,
        double loadImbalance,
        List<WorkerShare> perWorker) {

    /** Copies the list. */
    public DistributedMetrics {
        perWorker = List.copyOf(perWorker);
    }
}
