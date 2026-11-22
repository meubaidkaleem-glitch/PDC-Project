package org.districompute.distributed.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.districompute.core.algorithm.AlgorithmId;

/**
 * Per-algorithm performance observations of one worker. Kept across reconnections of the same worker id. Not
 * thread-safe: owned by the coordinator's control loop.
 */
public final class PerformanceHistory {

    /** Weight of the newest observation in the moving averages. */
    public static final double ALPHA = 0.3;

    private final Map<AlgorithmId, Accumulator> byAlgorithm = new LinkedHashMap<>();

    private static final class Accumulator {
        private long samples;
        private double operationsPerSecond;
        private double transferBytesPerSecond;
        private double kernelMillisSum;
    }

    /**
     * Records one completed attempt.
     *
     * @param algorithm algorithm id
     * @param operations estimated operations of the partition
     * @param kernelNanos kernel time reported by the worker
     * @param transferredBytes bytes of the work unit and the result frames
     * @param transferNanos transport time measured for the attempt
     */
    public void observe(AlgorithmId algorithm, double operations, long kernelNanos, long transferredBytes,
            long transferNanos) {
        Accumulator acc = byAlgorithm.computeIfAbsent(algorithm, id -> new Accumulator());
        if (kernelNanos > 0 && operations > 0) {
            double rate = operations / (kernelNanos / 1e9);
            acc.operationsPerSecond = acc.samples == 0 ? rate : ALPHA * rate + (1 - ALPHA) * acc.operationsPerSecond;
        }
        if (transferNanos > 0 && transferredBytes > 0) {
            double rate = transferredBytes / (transferNanos / 1e9);
            acc.transferBytesPerSecond = acc.transferBytesPerSecond == 0 ? rate
                    : ALPHA * rate + (1 - ALPHA) * acc.transferBytesPerSecond;
        }
        acc.kernelMillisSum += kernelNanos / 1e6;
        acc.samples++;
    }

    /**
     * Statistics for one algorithm.
     *
     * @param algorithm algorithm id
     * @return the statistics, if the worker completed at least one attempt of it
     */
    public Optional<PerformanceStats> stats(AlgorithmId algorithm) {
        Accumulator acc = byAlgorithm.get(algorithm);
        return acc == null ? Optional.empty() : Optional.of(toStats(algorithm, acc));
    }

    /**
     * Statistics for all algorithms.
     *
     * @return one entry per algorithm, in first-observation order
     */
    public List<PerformanceStats> all() {
        List<PerformanceStats> list = new ArrayList<>(byAlgorithm.size());
        byAlgorithm.forEach((id, acc) -> list.add(toStats(id, acc)));
        return list;
    }

    private static PerformanceStats toStats(AlgorithmId id, Accumulator acc) {
        return new PerformanceStats(id, acc.samples, acc.operationsPerSecond, acc.transferBytesPerSecond,
                acc.samples == 0 ? 0 : acc.kernelMillisSum / acc.samples);
    }
}
