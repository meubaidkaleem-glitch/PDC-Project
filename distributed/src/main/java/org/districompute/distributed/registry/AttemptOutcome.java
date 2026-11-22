package org.districompute.distributed.registry;

/** Outcomes counted per worker. */
public enum AttemptOutcome {
    /** Core accepted the result. */
    SUCCEEDED,
    /** The worker reported a failure. */
    FAILED,
    /** The worker refused the attempt. */
    REJECTED,
    /** The attempt exceeded its timeout. */
    TIMED_OUT,
    /** The attempt was lost with the connection or missing from a heartbeat. */
    LOST,
    /** Core classified the result as invalid or as a conflicting duplicate. */
    INVALID_RESULT,
    /** Core classified the result as an identical duplicate. */
    DUPLICATE_RESULT
}
