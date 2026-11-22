package org.districompute.distributed.task;

import java.time.Instant;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.scheduler.PolicyName;

/**
 * A task without its per-partition detail, for task lists.
 *
 * @param taskId task id
 * @param experimentId experiment id, or {@code null}
 * @param algorithm requested algorithm
 * @param status Core task status
 * @param policy scheduling policy
 * @param submittedAt submission time
 * @param finishedAt end time, or {@code null}
 * @param totalPartitions planned partitions
 * @param completedPartitions completed partitions
 * @param attempts attempts so far
 * @param retries retries so far
 * @param error error of a failed or cancelled task, or {@code null}
 */
public record TaskSummary(String taskId, String experimentId, AlgorithmRef algorithm, TaskStatus status,
        PolicyName policy, Instant submittedAt, Instant finishedAt, int totalPartitions, int completedPartitions,
        int attempts, int retries, ErrorInfo error) {

    /**
     * Summarizes a snapshot.
     *
     * @param snapshot the snapshot
     * @return the summary
     */
    public static TaskSummary of(TaskSnapshot snapshot) {
        return new TaskSummary(snapshot.taskId(), snapshot.experimentId(), snapshot.algorithm(), snapshot.status(),
                snapshot.policy(), snapshot.submittedAt(), snapshot.finishedAt(), snapshot.totalPartitions(),
                snapshot.completedPartitions(), snapshot.attempts(), snapshot.retries(), snapshot.error());
    }
}
