package org.districompute.distributed.recovery;

/**
 * Classification of a failed attempt ({@code docs/fault-tolerance.md}). Failures that say something about the
 * partition or the worker count against the partition's attempt budget; refusals and coordinator-side problems
 * have a separate, larger budget, so a busy cluster cannot exhaust a task's retries.
 */
public enum FailureKind {
    /** The worker's connection closed or its heartbeats stopped. */
    WORKER_LOST(true),
    /** The worker's heartbeat no longer lists an attempt it had accepted, and no result arrived. */
    MISSING_FROM_HEARTBEAT(true),
    /** The attempt exceeded its timeout (reported by the worker or detected by the coordinator). */
    TIMED_OUT(true),
    /** The worker reported that the kernel failed. */
    TASK_FAILED(true),
    /** Core rejected the result (checksum, decoding, bounds, wrong partition). */
    INVALID_RESULT(true),
    /** The worker refused the attempt (capacity, unsupported algorithm, memory). */
    REJECTED(false),
    /** The coordinator could not send the work unit (connection closed, frame too large for the worker). */
    DISPATCH_FAILED(false),
    /** The worker never confirmed the attempt with {@code TASK_ACCEPTED}. */
    NOT_ACCEPTED(true),
    /** The worker cancelled the attempt because it is stopping. */
    WORKER_STOPPED(false),
    /** The worker was quarantined, so its running attempts are moved elsewhere. */
    WORKER_QUARANTINED(false);

    private final boolean countsAgainstBudget;

    FailureKind(boolean countsAgainstBudget) {
        this.countsAgainstBudget = countsAgainstBudget;
    }

    /**
     * Whether the failure uses up one of the partition's attempts.
     *
     * @return {@code true} if counted
     */
    public boolean countsAgainstBudget() {
        return countsAgainstBudget;
    }
}
