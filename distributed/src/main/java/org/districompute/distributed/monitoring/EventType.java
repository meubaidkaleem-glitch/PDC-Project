package org.districompute.distributed.monitoring;

/** Kinds of runtime events ({@code docs/integration-contract.md}, "Events"). */
public enum EventType {
    /** The coordinator started accepting workers. */
    COORDINATOR_STARTED,
    /** The coordinator is shutting down. */
    COORDINATOR_STOPPING,
    /** A worker registered (or registered again after a reconnection). */
    WORKER_REGISTERED,
    /** A registration was refused (bad proof, duplicate id, limits, policy). */
    WORKER_REJECTED,
    /** A worker's status changed (for example ACTIVE to SUSPECT). */
    WORKER_STATUS_CHANGED,
    /** A worker disconnected or was declared unavailable. */
    WORKER_DISCONNECTED,
    /** A task was submitted. */
    TASK_SUBMITTED,
    /** A task's Core status changed (VALIDATING, QUEUED, RUNNING, AGGREGATING, ...). */
    TASK_STATUS_CHANGED,
    /** A partition of a task completed. */
    TASK_PROGRESS,
    /** A task ended: COMPLETED, FAILED or CANCELLED, with its metrics. */
    TASK_FINISHED,
    /** An attempt was assigned to a worker (with the scheduling decision). */
    ATTEMPT_ASSIGNED,
    /** A worker accepted an attempt. */
    ATTEMPT_ACCEPTED,
    /** A worker reported kernel progress for an attempt. */
    ATTEMPT_PROGRESS,
    /** Core accepted an attempt's result. */
    ATTEMPT_SUCCEEDED,
    /** An attempt ended without an accepted result (rejected, failed, timed out, lost, invalid, cancelled). */
    ATTEMPT_FAILED,
    /** A result was ignored: a duplicate or a result for a task that had already ended. */
    RESULT_IGNORED,
    /** A partition was queued again after a failure. */
    PARTITION_RETRIED,
    /** The default scheduling policy changed. */
    SCHEDULING_POLICY_CHANGED,
    /** A subscriber missed events because it could not keep up; it should re-read the state. */
    EVENTS_DROPPED
}
