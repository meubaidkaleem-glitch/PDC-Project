package org.districompute.distributed.scheduler;

import java.time.Instant;
import java.util.List;

/**
 * The observable record of one scheduling decision: which worker was chosen for which attempt, by which policy,
 * in which worker state, and why ({@code docs/scheduling.md}, "Decision records").
 *
 * @param decidedAt when the decision was made
 * @param taskId task id
 * @param partitionId partition id
 * @param attemptId attempt id
 * @param policy policy that decided
 * @param workerId selected worker
 * @param workerState the selected worker's state when it was chosen
 * @param explanation human-readable reason for the choice
 * @param eligibleWorkers number of eligible workers
 * @param rejected workers that were not eligible, with reasons
 * @param decisionNanos time spent deciding
 */
public record AssignmentRecord(Instant decidedAt, String taskId, String partitionId, String attemptId,
        PolicyName policy, String workerId, Candidate workerState, String explanation, int eligibleWorkers,
        List<Rejection> rejected, long decisionNanos) {

    /** Copies the list. */
    public AssignmentRecord {
        rejected = List.copyOf(rejected);
    }
}
