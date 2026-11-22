package org.districompute.distributed.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Capability-aware: among workers that support the algorithm, its version and the attempt's memory and message
 * size (the eligibility rules), prefer the one with the most spare capacity: idle processor cores first
 * ({@code freeSlots × cores / slots}), then memory headroom over the attempt's estimated need.
 */
public final class CapabilityAwarePolicy implements SchedulingPolicy {

    @Override
    public PolicyName name() {
        return PolicyName.CAPABILITY_AWARE;
    }

    @Override
    public Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos) {
        long need = Math.max(1, request.requirements().memoryBytes());
        Comparator<Candidate> byCapacity = Comparator.comparingDouble(CapabilityAwarePolicy::idleCores)
                .thenComparingDouble(c -> (double) c.maxTaskMemoryBytes() / need);
        Candidate best = eligible.stream().max(byCapacity.thenComparing(Candidate::workerId,
                Comparator.reverseOrder())).orElseThrow();
        return new Choice(best, String.format(Locale.ROOT, "capability-aware: %s has %.1f idle cores (%d cores, %d of "
                + "%d slots busy) and %.1fx the memory the attempt needs; best of %d capable workers", best.workerId(),
                idleCores(best), best.cpuCores(), best.assigned(), best.maxSlots(),
                (double) best.maxTaskMemoryBytes() / need, eligible.size()));
    }

    /**
     * Cores not used by assigned attempts, assuming the worker spreads its cores evenly over its slots.
     *
     * @param candidate the worker
     * @return idle cores
     */
    static double idleCores(Candidate candidate) {
        return candidate.freeSlots() * (double) candidate.cpuCores() / Math.max(1, candidate.maxSlots());
    }
}
