package org.districompute.distributed.task;

import java.time.Instant;
import java.util.List;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.metrics.DistributedMetrics;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.strategy.StrategyOverride;

/**
 * Immutable view of a task in the runtime: Core's status, the partitions and their attempts, the runtime metrics
 * and, once finished, Core's {@code TaskResult}.
 *
 * @param taskId task id
 * @param experimentId experiment id, or {@code null}
 * @param algorithm requested algorithm
 * @param mode execution mode
 * @param status Core task status
 * @param policy scheduling policy (distributed tasks)
 * @param submittedAt when the task was submitted
 * @param finishedAt when it ended, or {@code null}
 * @param totalPartitions planned partitions (0 before planning)
 * @param completedPartitions partitions with an accepted result
 * @param attempts attempts dispatched so far
 * @param retries attempts beyond the first per partition
 * @param error why the task failed or was cancelled, or {@code null}
 * @param metrics distributed metrics (computation, communication and scheduling kept separate); {@code null} for
 *     tasks executed locally
 * @param partitions per-partition detail
 * @param result Core's final result, once the task has ended
 * @param override the recorded manual override of the recommended mode, or {@code null}
 */
public record TaskSnapshot(String taskId, String experimentId, AlgorithmRef algorithm, ExecutionMode mode,
        TaskStatus status, PolicyName policy, Instant submittedAt, Instant finishedAt, int totalPartitions,
        int completedPartitions, int attempts, int retries, ErrorInfo error, DistributedMetrics metrics,
        List<PartitionSnapshot> partitions, TaskResult result, StrategyOverride override) {

    /** Copies the list. */
    public TaskSnapshot {
        partitions = List.copyOf(partitions);
    }
}
