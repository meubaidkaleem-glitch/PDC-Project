package org.districompute.distributed.protocol;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/** Why the coordinator cancels an attempt. */
public enum CancelReason {
    /** The Application cancelled the task. */
    USER_REQUESTED,
    /** The attempt exceeded its timeout. */
    TIMEOUT,
    /** Another attempt of the same partition already delivered an accepted result. */
    SUPERSEDED,
    /** The task ended (completed, failed or cancelled), so the attempt is no longer needed. */
    TASK_FINISHED,
    /** The coordinator is shutting down. */
    SHUTDOWN,
    /** A reason this runtime does not know (sent by a newer peer). */
    @JsonEnumDefaultValue
    UNKNOWN
}
