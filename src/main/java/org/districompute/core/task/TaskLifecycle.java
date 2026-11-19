package org.districompute.core.task;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * Thread-safe state machine that tracks a task's {@link TaskStatus} and its history.
 *
 * <p>Several threads may drive one task, for example the executing thread and a thread that requests
 * cancellation. Every transition is checked against {@link TaskStatus#canTransitionTo}. Invalid transitions are
 * rejected with {@link ErrorCode#INVALID_STATE_TRANSITION}. Use {@link #tryTransitionTo} where a race is expected,
 * such as cancellation that arrives after completion.
 */
public final class TaskLifecycle {

    private final Clock clock;
    private final List<StatusChange> history = new ArrayList<>();
    private TaskStatus current;

    /** Creates a lifecycle in {@link TaskStatus#CREATED}, using the system UTC clock. */
    public TaskLifecycle() {
        this(Clock.systemUTC());
    }

    /**
     * Creates a lifecycle in {@link TaskStatus#CREATED}.
     *
     * @param clock clock used to timestamp status changes
     */
    public TaskLifecycle(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.current = TaskStatus.CREATED;
        history.add(new StatusChange(TaskStatus.CREATED, clock.instant()));
    }

    /**
     * Returns the current status.
     *
     * @return the current status
     */
    public synchronized TaskStatus current() {
        return current;
    }

    /**
     * Moves to {@code target}.
     *
     * @param target the next status
     * @throws DistriComputeException with {@link ErrorCode#INVALID_STATE_TRANSITION} if the transition is not
     *     permitted
     */
    public synchronized void transitionTo(TaskStatus target) {
        if (!tryTransitionTo(target)) {
            throw new DistriComputeException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Transition " + current + " -> " + target + " is not permitted");
        }
    }

    /**
     * Moves to {@code target} if the transition is permitted.
     *
     * @param target the next status
     * @return {@code true} if the transition happened, {@code false} if it is not permitted from the current status
     */
    public synchronized boolean tryTransitionTo(TaskStatus target) {
        if (!current.canTransitionTo(target)) {
            return false;
        }
        current = target;
        history.add(new StatusChange(target, clock.instant()));
        return true;
    }

    /**
     * Returns the status history, oldest first.
     *
     * @return an immutable snapshot of the history
     */
    public synchronized List<StatusChange> history() {
        return List.copyOf(history);
    }
}
