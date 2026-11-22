package org.districompute.distributed.scheduler;

import java.util.List;

/**
 * Chooses one worker among eligible candidates. Eligibility (status, capability, version, memory, message size,
 * free slot) is checked before a policy is consulted, so a policy only ranks workers that can run the attempt.
 * Implementations must be deterministic for the same inputs and explain their choice.
 */
public interface SchedulingPolicy {

    /**
     * The policy's name.
     *
     * @return the name
     */
    PolicyName name();

    /**
     * Chooses a worker.
     *
     * @param request the attempt to place
     * @param eligible eligible candidates, never empty, in worker-id order
     * @param nowNanos current monotonic time
     * @return the choice and its explanation
     */
    Choice choose(SchedulingRequest request, List<Candidate> eligible, long nowNanos);

    /**
     * A policy's choice.
     *
     * @param selected the chosen candidate
     * @param explanation why
     */
    record Choice(Candidate selected, String explanation) {
    }
}
