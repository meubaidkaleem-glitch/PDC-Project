package org.districompute.core.partition;

/**
 * Runtime status of a partition as seen by Core. The distributed runtime may track dispatch states of its own
 * (for example "assigned to worker"). It maps them onto these values when it reports to Core.
 */
public enum PartitionStatus {
    /** No valid result yet and not currently failed. */
    PENDING,
    /** Being computed. */
    RUNNING,
    /** A valid partial result has been accepted. */
    COMPLETED,
    /** The last attempt failed; eligible for retry if the partition is retryable. */
    FAILED,
    /** Abandoned because the task was cancelled. */
    CANCELLED
}
