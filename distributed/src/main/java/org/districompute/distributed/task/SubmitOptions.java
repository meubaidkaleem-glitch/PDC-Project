package org.districompute.distributed.task;

import java.time.Duration;
import org.districompute.distributed.scheduler.PolicyName;

/**
 * Runtime options of one submitted task. {@code null} fields take the coordinator's defaults.
 *
 * @param policy scheduling policy for this task
 * @param partitionsPerSlot partitions planned per free worker slot when the task does not set its own partitioning
 * @param attemptTimeout time allowed for one attempt
 * @param maxAttempts attempts per partition before the task fails (including the first)
 * @param maxWorkers use at most this many distinct workers (limited availability experiments)
 * @param overrideReason why the requested mode differs from the recommendation, if it does (recorded)
 */
public record SubmitOptions(PolicyName policy, Integer partitionsPerSlot, Duration attemptTimeout, Integer maxAttempts,
        Integer maxWorkers, String overrideReason) {

    /** All defaults. */
    public static final SubmitOptions DEFAULTS = new SubmitOptions(null, null, null, null, null, null);

    /** Validates the components. */
    public SubmitOptions {
        if (partitionsPerSlot != null && (partitionsPerSlot < 1 || partitionsPerSlot > 64)) {
            throw new IllegalArgumentException("partitionsPerSlot must be between 1 and 64");
        }
        if (attemptTimeout != null && (attemptTimeout.isNegative() || attemptTimeout.isZero())) {
            throw new IllegalArgumentException("attemptTimeout must be positive");
        }
        if (maxAttempts != null && (maxAttempts < 1 || maxAttempts > 20)) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 20");
        }
        if (maxWorkers != null && maxWorkers < 1) {
            throw new IllegalArgumentException("maxWorkers must be at least 1");
        }
        if (overrideReason != null && overrideReason.length() > 500) {
            throw new IllegalArgumentException("overrideReason must have at most 500 characters");
        }
    }

    /**
     * Copy with another policy.
     *
     * @param value the policy
     * @return the new options
     */
    public SubmitOptions withPolicy(PolicyName value) {
        return new SubmitOptions(value, partitionsPerSlot, attemptTimeout, maxAttempts, maxWorkers, overrideReason);
    }

    /**
     * Copy with another number of partitions per slot.
     *
     * @param value partitions per free slot
     * @return the new options
     */
    public SubmitOptions withPartitionsPerSlot(int value) {
        return new SubmitOptions(policy, value, attemptTimeout, maxAttempts, maxWorkers, overrideReason);
    }

    /**
     * Copy with another attempt timeout.
     *
     * @param value the timeout
     * @return the new options
     */
    public SubmitOptions withAttemptTimeout(Duration value) {
        return new SubmitOptions(policy, partitionsPerSlot, value, maxAttempts, maxWorkers, overrideReason);
    }

    /**
     * Copy with another attempt limit.
     *
     * @param value attempts per partition
     * @return the new options
     */
    public SubmitOptions withMaxAttempts(int value) {
        return new SubmitOptions(policy, partitionsPerSlot, attemptTimeout, value, maxWorkers, overrideReason);
    }

    /**
     * Copy with the reason for overriding the recommended mode.
     *
     * @param value the reason
     * @return the new options
     */
    public SubmitOptions withOverrideReason(String value) {
        return new SubmitOptions(policy, partitionsPerSlot, attemptTimeout, maxAttempts, maxWorkers, value);
    }

    /**
     * Copy with a worker limit.
     *
     * @param value distinct workers at most
     * @return the new options
     */
    public SubmitOptions withMaxWorkers(int value) {
        return new SubmitOptions(policy, partitionsPerSlot, attemptTimeout, maxAttempts, value, overrideReason);
    }
}
