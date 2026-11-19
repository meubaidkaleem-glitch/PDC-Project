package org.districompute.core.task;

import java.time.Instant;
import java.util.Objects;

/**
 * One entry of a task's status history.
 *
 * @param status the status entered
 * @param at when it was entered
 */
public record StatusChange(TaskStatus status, Instant at) {

    /** Validates the components. */
    public StatusChange {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(at, "at");
    }
}
