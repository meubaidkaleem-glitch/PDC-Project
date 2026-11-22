package org.districompute.distributed.recovery;

import java.util.Objects;

/**
 * Decides whether a failed partition is attempted again ({@code docs/fault-tolerance.md}, "Retry policy").
 * A partition is retried only if all of these hold:
 * <ol>
 *   <li>the failure is retryable (the worker or Core says repeating could succeed);</li>
 *   <li>the partition is retry-safe: Core marks it deterministic and free of side effects ({@code retryable}),
 *       and it has no accepted result yet;</li>
 *   <li>the attempt budget ({@code maxAttempts} counted failures) and the refusal budget are not exhausted.</li>
 * </ol>
 * Otherwise the task fails with a message naming the reason. Repeated failures are never turned into success.
 */
public final class RetryPolicy {

    /**
     * The decision.
     *
     * @param retry whether to schedule another attempt
     * @param reason why not, when {@code retry} is false
     */
    public record Decision(boolean retry, String reason) {
    }

    private final int maxRejections;

    /**
     * Creates a policy.
     *
     * @param maxRejections refusals tolerated per partition
     */
    public RetryPolicy(int maxRejections) {
        if (maxRejections < 1) {
            throw new IllegalArgumentException("maxRejections must be at least 1");
        }
        this.maxRejections = maxRejections;
    }

    /**
     * Decides about a partition whose latest attempt failed and which has no other live attempt.
     *
     * @param kind the failure
     * @param failureRetryable whether the failure itself allows repetition
     * @param partitionRetrySafe whether Core allows re-executing the partition
     * @param countedFailures failures counted against the budget so far, including this one if it counts
     * @param maxAttempts the task's attempt budget
     * @param rejections refusals so far
     * @return retry, or fail with a reason
     */
    public Decision decide(FailureKind kind, boolean failureRetryable, boolean partitionRetrySafe,
            int countedFailures, int maxAttempts, int rejections) {
        Objects.requireNonNull(kind, "kind");
        if (!failureRetryable) {
            return new Decision(false, "the failure is not retryable");
        }
        if (!partitionRetrySafe) {
            return new Decision(false, "the partition is not retry-safe (Core does not mark it as deterministic "
                    + "and free of side effects)");
        }
        if (countedFailures >= maxAttempts) {
            return new Decision(false, "the retry budget is exhausted (" + countedFailures + " failed attempts, limit "
                    + maxAttempts + ")");
        }
        if (rejections >= maxRejections) {
            return new Decision(false, "the retry budget is exhausted (" + rejections + " refusals, limit "
                    + maxRejections + ")");
        }
        return new Decision(true, null);
    }
}
