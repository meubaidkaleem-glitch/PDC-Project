package org.districompute.distributed.monitoring;

/**
 * The ids an event refers to; any may be {@code null}.
 *
 * @param workerId worker id
 * @param experimentId experiment id
 * @param taskId task id
 * @param partitionId partition id
 * @param attemptId attempt id
 */
public record EventIds(String workerId, String experimentId, String taskId, String partitionId, String attemptId) {

    /** No ids. */
    public static final EventIds NONE = new EventIds(null, null, null, null, null);

    /**
     * Ids of a worker.
     *
     * @param workerId worker id
     * @return the ids
     */
    public static EventIds worker(String workerId) {
        return new EventIds(workerId, null, null, null, null);
    }
}
