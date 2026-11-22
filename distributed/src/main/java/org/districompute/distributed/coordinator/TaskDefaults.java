package org.districompute.distributed.coordinator;

import java.time.Duration;
import java.util.Objects;
import org.districompute.distributed.scheduler.PolicyName;

/**
 * Coordinator-wide defaults and limits for tasks ({@code docs/scheduling.md}, {@code docs/fault-tolerance.md}).
 * Each task can override the first four through {@code SubmitOptions}.
 *
 * @param policy default scheduling policy
 * @param partitionsPerSlot partitions planned per free eligible worker slot (over-decomposition for balancing)
 * @param attemptTimeout time allowed for one attempt
 * @param maxAttempts attempts per partition, counting failures (so {@code maxAttempts - 1} retries)
 * @param maxActiveTasks tasks running at the same time; more submissions are refused
 * @param maxPendingPartitions partitions waiting for a worker, across all tasks; bounds coordinator memory
 * @param noWorkerTimeout how long a task may wait without any capable worker before it fails
 * @param retainedTasks finished tasks kept for queries
 * @param maxRejectionsPerPartition worker refusals tolerated per partition before the task fails
 * @param quarantineThreshold invalid or conflicting results from one worker registration before it is quarantined
 * @param acceptTimeout time for a worker to confirm a dispatched attempt with {@code TASK_ACCEPTED}
 */
public record TaskDefaults(PolicyName policy, int partitionsPerSlot, Duration attemptTimeout, int maxAttempts,
        int maxActiveTasks, int maxPendingPartitions, Duration noWorkerTimeout, int retainedTasks,
        int maxRejectionsPerPartition, int quarantineThreshold, Duration acceptTimeout) {

    /** The defaults documented in {@code docs/scheduling.md}. */
    public static final TaskDefaults DEFAULTS = new TaskDefaults(PolicyName.LEAST_LOADED, 2, Duration.ofMinutes(10),
            3, 16, 100_000, Duration.ofSeconds(30), 1000, 20, 3, Duration.ofSeconds(30));

    /** Validates the components. */
    public TaskDefaults {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(attemptTimeout, "attemptTimeout");
        Objects.requireNonNull(noWorkerTimeout, "noWorkerTimeout");
        Objects.requireNonNull(acceptTimeout, "acceptTimeout");
        if (partitionsPerSlot < 1 || maxAttempts < 1 || maxActiveTasks < 1 || maxPendingPartitions < 1
                || retainedTasks < 0 || maxRejectionsPerPartition < 1 || quarantineThreshold < 1) {
            throw new IllegalArgumentException("Task limits must be positive");
        }
        if (attemptTimeout.isNegative() || attemptTimeout.isZero() || noWorkerTimeout.isNegative()
                || acceptTimeout.isNegative() || acceptTimeout.isZero()) {
            throw new IllegalArgumentException("Task timeouts must be positive");
        }
    }

    /**
     * Copy with another default policy.
     *
     * @param value the policy
     * @return the new defaults
     */
    public TaskDefaults withPolicy(PolicyName value) {
        return new TaskDefaults(value, partitionsPerSlot, attemptTimeout, maxAttempts, maxActiveTasks,
                maxPendingPartitions, noWorkerTimeout, retainedTasks, maxRejectionsPerPartition, quarantineThreshold,
                acceptTimeout);
    }

    /**
     * Copy with another attempt timeout.
     *
     * @param value the timeout
     * @return the new defaults
     */
    public TaskDefaults withAttemptTimeout(Duration value) {
        return new TaskDefaults(policy, partitionsPerSlot, value, maxAttempts, maxActiveTasks, maxPendingPartitions,
                noWorkerTimeout, retainedTasks, maxRejectionsPerPartition, quarantineThreshold, acceptTimeout);
    }

    /**
     * Copy with another no-worker timeout.
     *
     * @param value the timeout
     * @return the new defaults
     */
    public TaskDefaults withNoWorkerTimeout(Duration value) {
        return new TaskDefaults(policy, partitionsPerSlot, attemptTimeout, maxAttempts, maxActiveTasks,
                maxPendingPartitions, value, retainedTasks, maxRejectionsPerPartition, quarantineThreshold,
                acceptTimeout);
    }

    /**
     * Copy with another quarantine threshold.
     *
     * @param value invalid results per registration before quarantine
     * @return the new defaults
     */
    public TaskDefaults withQuarantineThreshold(int value) {
        return new TaskDefaults(policy, partitionsPerSlot, attemptTimeout, maxAttempts, maxActiveTasks,
                maxPendingPartitions, noWorkerTimeout, retainedTasks, maxRejectionsPerPartition, value, acceptTimeout);
    }

    /**
     * Copy with another acceptance timeout.
     *
     * @param value time for a worker to confirm an attempt
     * @return the new defaults
     */
    public TaskDefaults withAcceptTimeout(Duration value) {
        return new TaskDefaults(policy, partitionsPerSlot, attemptTimeout, maxAttempts, maxActiveTasks,
                maxPendingPartitions, noWorkerTimeout, retainedTasks, maxRejectionsPerPartition, quarantineThreshold,
                value);
    }

    /**
     * Copy with other task limits.
     *
     * @param activeTasks tasks at the same time
     * @param pendingPartitions partitions waiting across tasks
     * @return the new defaults
     */
    public TaskDefaults withLimits(int activeTasks, int pendingPartitions) {
        return new TaskDefaults(policy, partitionsPerSlot, attemptTimeout, maxAttempts, activeTasks,
                pendingPartitions, noWorkerTimeout, retainedTasks, maxRejectionsPerPartition, quarantineThreshold,
                acceptTimeout);
    }
}
