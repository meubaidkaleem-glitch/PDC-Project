package org.districompute.core;

import java.util.List;
import java.util.Objects;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.Violation;

/**
 * Serializable description of a failure (integration contract, section 9).
 *
 * @param code machine-readable error code
 * @param message human-readable explanation
 * @param retryable whether re-executing the same request could plausibly succeed
 * @param status task status in which the error occurred, or {@code null} if not tied to a task
 * @param partitionIndex index of the failed partition, or {@code null} if not partition-specific
 * @param violations field-level validation problems; empty if not a validation error
 */
public record ErrorInfo(
        ErrorCode code,
        String message,
        boolean retryable,
        TaskStatus status,
        Integer partitionIndex,
        List<Violation> violations) {

    /** Validates and normalizes the components. */
    public ErrorInfo {
        Objects.requireNonNull(code, "code");
        message = message == null || message.isBlank() ? code.name() : message;
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /**
     * Creates an error that is not tied to a task status or partition.
     *
     * @param code error code; its default retryability is used
     * @param message explanation
     * @return the error
     */
    public static ErrorInfo of(ErrorCode code, String message) {
        return new ErrorInfo(code, message, code.retryable(), null, null, List.of());
    }
}
