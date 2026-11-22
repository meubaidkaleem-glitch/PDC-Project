package org.districompute.distributed.scheduler;

import java.util.List;

/**
 * Round robin: eligible workers in a fixed rotation by worker id. The rotation continues across tasks; a worker that
 * is not eligible at the moment (busy, suspect, incapable) is skipped, not waited for.
 */
public final class RoundRobinPolicy implements SchedulingPolicy {

    private String last;

    @Override
    public PolicyName name() {
        return PolicyName.ROUND_ROBIN;
    }

    @Override
    public Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos) {
        Candidate next = eligible.get(0);
        if (last != null) {
            for (Candidate candidate : eligible) {
                if (candidate.workerId().compareTo(last) > 0) {
                    next = candidate;
                    break;
                }
            }
        }
        String explanation = last == null ? "round robin: " + next.workerId() + " starts the rotation"
                : "round robin: " + next.workerId() + " follows " + last + " among "
                        + eligible.stream().map(Candidate::workerId).toList();
        last = next.workerId();
        return new Choice(next, explanation);
    }
}
