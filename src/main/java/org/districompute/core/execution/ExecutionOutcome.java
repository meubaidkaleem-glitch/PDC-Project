package org.districompute.core.execution;

import java.util.Objects;
import java.util.Optional;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskStatus;

/**
 * What {@link LocalExecutionEngine#execute} returns: the serializable {@link TaskResult} plus, for in-process
 * callers, the full result value (for example the whole product matrix). The value is present only when the task
 * {@link TaskStatus#COMPLETED completed}, so an unverified value can never be mistaken for a result.
 *
 * @param taskResult serializable outcome
 * @param value full result value, or {@code null} unless completed
 */
public record ExecutionOutcome(TaskResult taskResult, Object value) {

    /** Validates the components. */
    public ExecutionOutcome {
        Objects.requireNonNull(taskResult, "taskResult");
        if (value != null && taskResult.status() != TaskStatus.COMPLETED) {
            throw new IllegalArgumentException("A value is only exposed for completed tasks");
        }
    }

    /**
     * Returns the full result value with the expected type.
     *
     * @param type expected type, for example {@code Matrix.class}
     * @param <T> expected type
     * @return the value, if the task completed
     */
    public <T> Optional<T> value(Class<T> type) {
        return Optional.ofNullable(value).map(type::cast);
    }

    /**
     * Shorthand for {@code taskResult().status()}.
     *
     * @return the terminal status
     */
    public TaskStatus status() {
        return taskResult.status();
    }
}
