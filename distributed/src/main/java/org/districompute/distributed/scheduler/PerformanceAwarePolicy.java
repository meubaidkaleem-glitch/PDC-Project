package org.districompute.distributed.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Performance-aware: estimates how long the attempt would take on each worker from that worker's observed history
 * for the same algorithm (kernel throughput and transfer rate, both moving averages), and picks the shortest.
 *
 * <p>A worker without observations for the algorithm is tried first (exploration), so every worker gets measured
 * and a newly joined fast machine is not ignored. Estimates are only as good as the history; they say nothing about
 * a worker whose load changed since.
 */
public final class PerformanceAwarePolicy implements SchedulingPolicy {

    @Override
    public PolicyName name() {
        return PolicyName.PERFORMANCE_AWARE;
    }

    @Override
    public Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos) {
        Optional<Candidate> unmeasured = eligible.stream().filter(c -> c.operationsPerSecond() <= 0).findFirst();
        if (unmeasured.isPresent()) {
            return new Choice(unmeasured.get(), "performance-aware: " + unmeasured.get().workerId()
                    + " has no observations for " + request.requirements().algorithm().id()
                    + " yet, so it is measured first");
        }
        Candidate best = eligible.stream().min(Comparator.comparingDouble((Candidate c) -> estimate(request, c))
                .thenComparing(Candidate::workerId)).orElseThrow();
        StringBuilder others = new StringBuilder();
        eligible.stream().filter(c -> c != best).limit(3).forEach(c -> others.append(String.format(Locale.ROOT,
                "; %s %.3f s", c.workerId(), estimate(request, c))));
        return new Choice(best, String.format(Locale.ROOT, "performance-aware: %s estimated %.3f s (%.3g ops/s over "
                + "%d attempts%s)%s", best.workerId(), estimate(request, best), best.operationsPerSecond(),
                best.samples(), best.transferBytesPerSecond() > 0 ? String.format(Locale.ROOT, ", %.1f MB/s transfer",
                        best.transferBytesPerSecond() / 1e6) : "", others));
    }

    /**
     * Estimated seconds for the attempt on a measured worker: kernel time from the observed throughput plus
     * transfer time for the work unit at the observed rate (when known).
     *
     * @param request the attempt
     * @param candidate a worker with observations
     * @return seconds
     */
    static double estimate(SchedulingRequest request, Candidate candidate) {
        double compute = request.requirements().operationsPerPartition() / candidate.operationsPerSecond();
        double transfer = candidate.transferBytesPerSecond() > 0
                ? request.requirements().payloadBytes() / candidate.transferBytesPerSecond() : 0;
        return compute + transfer;
    }
}
