package org.districompute.distributed.recovery;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(5);

    @Test
    void retriesRetryableFailuresOfRetrySafePartitionsWithinTheBudget() {
        assertTrue(policy.decide(FailureKind.WORKER_LOST, true, true, 2, 3, 0).retry());
        assertTrue(policy.decide(FailureKind.REJECTED, true, true, 0, 3, 4).retry());
    }

    @Test
    void refusesWithAReason() {
        assertTrue(policy.decide(FailureKind.TASK_FAILED, false, true, 1, 3, 0).reason().contains("not retryable"));
        assertTrue(policy.decide(FailureKind.TIMED_OUT, true, false, 1, 3, 0).reason().contains("not retry-safe"));
        assertTrue(policy.decide(FailureKind.INVALID_RESULT, true, true, 3, 3, 0).reason()
                .contains("3 failed attempts, limit 3"));
        assertTrue(policy.decide(FailureKind.REJECTED, true, true, 0, 3, 5).reason().contains("5 refusals"));
    }

    @Test
    void refusalsAndCoordinatorProblemsDoNotUseUpTheAttemptBudget() {
        assertFalse(FailureKind.REJECTED.countsAgainstBudget());
        assertFalse(FailureKind.DISPATCH_FAILED.countsAgainstBudget());
        assertFalse(FailureKind.WORKER_STOPPED.countsAgainstBudget());
        assertTrue(FailureKind.WORKER_LOST.countsAgainstBudget());
        assertTrue(FailureKind.INVALID_RESULT.countsAgainstBudget());
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0));
    }
}
