package org.districompute.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.Violation;

/**
 * Base exception for all failures that DistriCompute-Core reports. Every instance carries an {@link ErrorCode},
 * so callers can translate it into the shared error model ({@link ErrorInfo}) without parsing messages.
 *
 * <p>Instances are never sent across process boundaries with Java serialization. Use {@link #toErrorInfo} and
 * the JSON contract codec instead.
 */
public class DistriComputeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    /** Transient: Java serialization of exceptions is not part of the contract. */
    private final transient List<Violation> violations;
    private final Integer partitionIndex;

    /**
     * Creates an exception with a code and message.
     *
     * @param code error code
     * @param message explanation
     */
    public DistriComputeException(ErrorCode code, String message) {
        this(code, message, null, List.of(), null);
    }

    /**
     * Creates an exception with a code, message and cause.
     *
     * @param code error code
     * @param message explanation
     * @param cause underlying failure
     */
    public DistriComputeException(ErrorCode code, String message, Throwable cause) {
        this(code, message, cause, List.of(), null);
    }

    /**
     * Full constructor for subclasses and partition-specific failures.
     *
     * @param code error code
     * @param message explanation
     * @param cause underlying failure, may be {@code null}
     * @param violations field-level problems, may be empty
     * @param partitionIndex failed partition, may be {@code null}
     */
    protected DistriComputeException(
            ErrorCode code, String message, Throwable cause, List<Violation> violations, Integer partitionIndex) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
        this.violations = violations == null ? List.of() : List.copyOf(violations);
        this.partitionIndex = partitionIndex;
    }

    /**
     * Creates a partition-specific failure.
     *
     * @param code error code
     * @param partitionIndex index of the failed partition
     * @param message explanation
     * @param cause underlying failure, may be {@code null}
     * @return the exception
     */
    public static DistriComputeException forPartition(
            ErrorCode code, int partitionIndex, String message, Throwable cause) {
        return new DistriComputeException(code, message, cause, List.of(), partitionIndex);
    }

    /**
     * Returns the error code.
     *
     * @return the error code
     */
    public ErrorCode code() {
        return code;
    }

    /**
     * Returns field-level validation problems.
     *
     * @return the violations, empty if this is not a validation error
     */
    public List<Violation> violations() {
        return violations == null ? List.of() : violations;
    }

    /**
     * Returns the failed partition, if any.
     *
     * @return the partition index
     */
    public Optional<Integer> partitionIndex() {
        return Optional.ofNullable(partitionIndex);
    }

    /**
     * Converts this exception to the shared, serializable error model.
     *
     * @param status task status in which the failure occurred, may be {@code null}
     * @return the error description
     */
    public ErrorInfo toErrorInfo(TaskStatus status) {
        return new ErrorInfo(code, getMessage(), code.retryable(), status, partitionIndex, violations());
    }

    /**
     * Finds the first {@code DistriComputeException} in a cause chain. Executors often wrap exceptions (for example
     * {@code ExecutionException}), so callers use this helper to recover the original error code.
     *
     * @param failure any throwable
     * @return the first {@code DistriComputeException} in the chain, if present
     */
    public static Optional<DistriComputeException> findIn(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 32) {
            if (current instanceof DistriComputeException dce) {
                return Optional.of(dce);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
