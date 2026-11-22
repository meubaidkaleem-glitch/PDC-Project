package org.districompute.distributed.registry;

import org.districompute.core.algorithm.AlgorithmId;

/**
 * Observed performance of one worker for one algorithm, from completed attempts. Rates are exponentially weighted
 * moving averages, so they follow changes in the worker's load.
 *
 * @param algorithm algorithm id
 * @param samples completed attempts observed
 * @param operationsPerSecond kernel throughput in the algorithm's operation unit (Core's workload profile)
 * @param transferBytesPerSecond transfer rate between coordinator and worker, or 0 if unknown
 * @param meanKernelMillis average kernel time per attempt
 */
public record PerformanceStats(AlgorithmId algorithm, long samples, double operationsPerSecond,
        double transferBytesPerSecond, double meanKernelMillis) {
}
