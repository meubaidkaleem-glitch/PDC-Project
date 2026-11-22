package org.districompute.distributed.protocol;

import java.util.Objects;

/**
 * The ids that identify one attempt of one partition: Core's task and partition ids, the experiment id copied from
 * the task, and the runtime's attempt id.
 *
 * @param experimentId experiment id from the task, or {@code null}
 * @param taskId Core task id
 * @param partitionId Core partition id, {@code <taskId>/p<index>}
 * @param attemptId attempt id, {@code <partitionId>/a<attempt>}
 */
public record AttemptRef(String experimentId, String taskId, String partitionId, String attemptId) {

    /** Validates the components. */
    public AttemptRef {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(attemptId, "attemptId");
    }

    /**
     * The attempt number encoded in the attempt id.
     *
     * @return the number, or {@code -1} if the id is malformed
     */
    public int attempt() {
        return Ids.attemptNumber(partitionId, attemptId);
    }
}
