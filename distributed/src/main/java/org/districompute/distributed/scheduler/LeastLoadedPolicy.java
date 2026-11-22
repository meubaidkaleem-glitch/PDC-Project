package org.districompute.distributed.scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Least loaded: the candidate with the lowest slot utilization ({@code assigned / maxSlots}), so a worker with more
 * slots takes proportionally more attempts. Ties go to fewer assigned attempts, then to the worker id.
 */
public final class LeastLoadedPolicy implements SchedulingPolicy {

    @Override
    public PolicyName name() {
        return PolicyName.LEAST_LOADED;
    }

    @Override
    public Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos) {
        Candidate best = eligible.stream().min(Comparator.comparingDouble(Candidate::utilization)
                .thenComparingInt(Candidate::assigned).thenComparing(Candidate::workerId)).orElseThrow();
        return new Choice(best, String.format(Locale.ROOT, "least loaded: %s uses %d of %d slots (%.0f%%)%s",
                best.workerId(), best.assigned(), best.maxSlots(), 100 * best.utilization(),
                eligible.size() > 1 ? "; the lowest of " + eligible.size() + " eligible workers" : ""));
    }
}
