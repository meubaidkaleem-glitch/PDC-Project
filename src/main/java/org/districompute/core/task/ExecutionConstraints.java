package org.districompute.core.task;

import java.util.Objects;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitioningOptions;

/**
 * Execution constraints requested for a task.
 *
 * @param mode requested execution mode
 * @param maxThreads thread count for {@link ExecutionMode#MULTITHREADED_CPU}; {@code null} means the number of
 *     available processors
 * @param partitioning partitioning request; never {@code null} (defaults to "algorithm chooses")
 * @param timeoutMillis deadline for the whole execution in milliseconds, or {@code null} for none
 * @param maxMemoryBytes memory cap in bytes, or {@code null} to use the available heap
 */
public record ExecutionConstraints(
        ExecutionMode mode,
        Integer maxThreads,
        PartitioningOptions partitioning,
        Long timeoutMillis,
        Long maxMemoryBytes) {

    /** Validates the components and applies defaults. */
    public ExecutionConstraints {
        Objects.requireNonNull(mode, "mode");
        if (maxThreads != null && maxThreads < 1) {
            throw new IllegalArgumentException("maxThreads must be at least 1, got " + maxThreads);
        }
        if (timeoutMillis != null && timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be at least 1, got " + timeoutMillis);
        }
        if (maxMemoryBytes != null && maxMemoryBytes < 1) {
            throw new IllegalArgumentException("maxMemoryBytes must be at least 1, got " + maxMemoryBytes);
        }
        partitioning = partitioning == null ? PartitioningOptions.defaults() : partitioning;
    }

    /**
     * Constraints that only fix the mode.
     *
     * @param mode execution mode
     * @return the constraints
     */
    public static ExecutionConstraints of(ExecutionMode mode) {
        return new ExecutionConstraints(mode, null, null, null, null);
    }
}
