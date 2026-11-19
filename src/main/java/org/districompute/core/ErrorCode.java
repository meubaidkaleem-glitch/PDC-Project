package org.districompute.core;

/**
 * Machine-readable error codes shared by all DistriCompute components (integration contract, section 9).
 *
 * <p>{@link #retryable()} states whether re-executing the <em>same</em> request could plausibly succeed, that is,
 * whether the failed work is eligible for automatic recovery such as re-dispatching a partition to another worker.
 */
public enum ErrorCode {
    /** Parameters, dimensions or a submitted document are invalid. */
    VALIDATION_FAILED(false),
    /** The algorithm identifier is not registered. */
    UNKNOWN_ALGORITHM(false),
    /** No registered version satisfies the requested algorithm version. */
    UNSUPPORTED_ALGORITHM_VERSION(false),
    /** The requested execution mode is not implemented for this algorithm or by this component. */
    UNSUPPORTED_EXECUTION_MODE(false),
    /** A contract document uses an incompatible schema version. */
    UNSUPPORTED_SCHEMA_VERSION(false),
    /** The estimated memory requirement exceeds the available budget. */
    RESOURCE_EXHAUSTED(false),
    /** A lifecycle driver attempted a transition that the task state machine forbids. */
    INVALID_STATE_TRANSITION(false),
    /** A partition computation threw an exception. */
    PARTITION_FAILED(true),
    /** A partial result failed validation (wrong task, type, dimensions, bounds or checksum). */
    INVALID_PARTIAL_RESULT(true),
    /** Aggregation was requested while partial results are missing. */
    MISSING_PARTIAL_RESULT(true),
    /** Partial results are mutually inconsistent and cannot be combined. */
    AGGREGATION_FAILED(false),
    /** The aggregated result failed independent verification. */
    VERIFICATION_FAILED(true),
    /** A payload could not be decoded or its checksum did not match. */
    SERIALIZATION_FAILED(true),
    /** The execution deadline was exceeded. */
    TIMEOUT(true),
    /** Cancellation was requested. */
    CANCELLED(false),
    /** An unexpected exception occurred while executing. */
    EXECUTION_FAILED(true),
    /** A defect in DistriCompute-Core. */
    INTERNAL_ERROR(false);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * Whether re-executing the same request could plausibly succeed.
     *
     * @return {@code true} if the failed work is eligible for automatic retry
     */
    public boolean retryable() {
        return retryable;
    }
}
