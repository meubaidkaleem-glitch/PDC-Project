package org.districompute.distributed.task;

/** Dispatch state of one attempt of one partition. The runtime adds these to Core's partition status. */
public enum AttemptStatus {
    /** A worker was chosen; the work unit is being encoded and queued. */
    DISPATCHING,
    /** The {@code TASK} message was handed to the worker's connection. */
    DISPATCHED,
    /** The worker admitted the attempt ({@code TASK_ACCEPTED}). */
    ACCEPTED,
    /** The worker reported progress. */
    RUNNING,
    /** Core accepted the attempt's partial result. */
    SUCCEEDED,
    /** The worker refused the attempt. */
    REJECTED,
    /** The worker reported a failure, or the work unit could not be sent. */
    FAILED,
    /** The attempt exceeded its timeout. */
    TIMED_OUT,
    /** The worker disconnected, or the attempt disappeared from its heartbeat. */
    LOST,
    /** Core rejected the partial result (invalid, conflicting or misplaced). */
    INVALID,
    /** Cancelled at the coordinator's request (task cancelled or finished). */
    CANCELLED,
    /** Another attempt of the same partition delivered the accepted result first. */
    SUPERSEDED;

    /**
     * Whether the attempt has ended.
     *
     * @return {@code true} for every state after {@link #RUNNING}
     */
    public boolean isTerminal() {
        return ordinal() > RUNNING.ordinal();
    }
}
