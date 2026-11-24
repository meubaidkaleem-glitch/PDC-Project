package org.districompute.app.task;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * How to execute a task. Beginner mode sends only {@code mode} (usually the recommendation); advanced mode can set
 * the rest. {@code null} members use the components' defaults.
 *
 * @param mode Core execution mode
 * @param threads threads of a {@code MULTITHREADED_CPU} run
 * @param targetPartitions Core partitioning: number of partitions
 * @param partitionSize Core partitioning: elements per partition (range and chunk strategies)
 * @param blockRows Core partitioning: block rows (matrix strategies)
 * @param blockCols Core partitioning: block columns (matrix strategies)
 * @param partitionsPerSlot Distributed: partitions per free worker slot when partitioning is not set
 * @param schedulingPolicy Distributed scheduling policy
 * @param maxWorkers Distributed: at most this many workers
 * @param maxAttempts Distributed: attempts per partition
 * @param attemptTimeoutSeconds Distributed: limit of one attempt
 * @param timeoutSeconds Core: deadline of a run
 * @param maxMemoryMb Core: memory cap of a run
 * @param priority Core priority
 * @param repetitions runs of the same task (benchmark repetitions)
 */
public record ExecutionSettings(
        @NotBlank @Pattern(regexp = "SEQUENTIAL_CPU|MULTITHREADED_CPU|DISTRIBUTED_CPU|GPU|HYBRID") String mode,
        @Min(1) @Max(1024) Integer threads,
        @Min(1) @Max(1_000_000) Integer targetPartitions,
        @Min(1) Long partitionSize,
        @Min(1) @Max(1_000_000) Integer blockRows,
        @Min(1) @Max(1_000_000) Integer blockCols,
        @Min(1) @Max(64) Integer partitionsPerSlot,
        @Pattern(regexp = "ROUND_ROBIN|LEAST_LOADED|CAPABILITY_AWARE|PERFORMANCE_AWARE|DYNAMIC") String schedulingPolicy,
        @Min(1) @Max(10_000) Integer maxWorkers,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(86_400) Integer attemptTimeoutSeconds,
        @Min(1) @Max(86_400) Integer timeoutSeconds,
        @Min(1) @Max(1_048_576) Long maxMemoryMb,
        @Pattern(regexp = "LOW|NORMAL|HIGH") String priority,
        @Min(1) @Max(100) Integer repetitions) {

    /**
     * Settings with only a mode.
     *
     * @param mode Core execution mode
     * @return the settings
     */
    public static ExecutionSettings of(String mode) {
        return new ExecutionSettings(mode, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null);
    }

    /**
     * Repetitions, defaulting to one.
     *
     * @return at least 1
     */
    public int repetitionCount() {
        return repetitions == null ? 1 : repetitions;
    }

    /**
     * The same settings in another mode (batch submissions).
     *
     * @param newMode Core execution mode
     * @return the copy
     */
    public ExecutionSettings withMode(String newMode) {
        return new ExecutionSettings(newMode, threads, targetPartitions, partitionSize, blockRows, blockCols,
                partitionsPerSlot, schedulingPolicy, maxWorkers, maxAttempts, attemptTimeoutSeconds, timeoutSeconds,
                maxMemoryMb, priority, repetitions);
    }
}
