package org.districompute.core.task;

import java.util.Objects;

/**
 * Lifecycle status of a computational task (integration contract, section 3).
 *
 * <p>The forward path is {@code CREATED → VALIDATING → VALIDATED → PLANNED → QUEUED → RUNNING → AGGREGATING →
 * VERIFYING → COMPLETED}. Any non-terminal status may move to {@link #FAILED} or {@link #CANCELLED}. Terminal
 * statuses are final. {@link #COMPLETED} is only reachable from {@link #VERIFYING}, so a task is never reported as
 * completed merely because its execution threads stopped.
 */
public enum TaskStatus {
    /** Accepted but not yet examined. */
    CREATED,
    /** Input validation is running. */
    VALIDATING,
    /** Input validation passed. */
    VALIDATED,
    /** Execution mode, thread count and partition plan are fixed. */
    PLANNED,
    /** Waiting for execution capacity. */
    QUEUED,
    /** Partitions (or the sequential implementation) are executing. */
    RUNNING,
    /** All partial results are present and valid; the final result is being assembled. */
    AGGREGATING,
    /** The assembled result is being verified independently. */
    VERIFYING,
    /** Terminal: the result was aggregated and verified. */
    COMPLETED,
    /** Terminal: the task failed; an error description is available. */
    FAILED,
    /** Terminal: cancellation was requested and observed. */
    CANCELLED;

    /**
     * Whether this status is final.
     *
     * @return {@code true} for {@link #COMPLETED}, {@link #FAILED} and {@link #CANCELLED}
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * Whether the state machine permits moving from this status to {@code target}.
     *
     * @param target the proposed next status
     * @return {@code true} if the transition is permitted
     */
    public boolean canTransitionTo(TaskStatus target) {
        Objects.requireNonNull(target, "target");
        if (isTerminal()) {
            return false;
        }
        if (target == FAILED || target == CANCELLED) {
            return true;
        }
        return target == next();
    }

    private TaskStatus next() {
        return switch (this) {
            case CREATED -> VALIDATING;
            case VALIDATING -> VALIDATED;
            case VALIDATED -> PLANNED;
            case PLANNED -> QUEUED;
            case QUEUED -> RUNNING;
            case RUNNING -> AGGREGATING;
            case AGGREGATING -> VERIFYING;
            case VERIFYING -> COMPLETED;
            case COMPLETED, FAILED, CANCELLED -> null;
        };
    }
}
