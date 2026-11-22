package org.districompute.distributed.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Dynamic (demand-driven self-scheduling): the next attempt goes to the worker that has waited longest for work,
 * then to the one assigned least recently. Combined with over-decomposition (more partitions than slots), fast
 * workers ask for work more often and so take more partitions, without any performance model.
 */
public final class DynamicPolicy implements SchedulingPolicy {

    @Override
    public PolicyName name() {
        return PolicyName.DYNAMIC;
    }

    @Override
    public Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos) {
        Candidate best = eligible.stream().min(Comparator.comparingLong((Candidate c) -> -c.idleNanos())
                .thenComparingLong(Candidate::lastAssignedNanos).thenComparingInt(Candidate::assigned)
                .thenComparing(Candidate::workerId)).orElseThrow();
        String explanation = best.idleNanos() > 0
                ? String.format(Locale.ROOT, "dynamic: %s has waited longest for work (idle %.0f ms)", best.workerId(),
                        best.idleNanos() / 1e6)
                : "dynamic: no worker is idle; " + best.workerId() + " was assigned least recently and has a free slot";
        return new Choice(best, explanation);
    }
}
