package org.districompute.core.execution;

/** Why an execution was cancelled. */
public enum CancellationReason {
    /** A user or client asked for cancellation. */
    USER_REQUESTED,
    /** The execution deadline passed. */
    TIMEOUT,
    /** The engine is shutting down. */
    SHUTDOWN,
    /** Another part of the same execution failed, so the remaining work is abandoned. */
    FAILURE
}
