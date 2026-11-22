package org.districompute.distributed.scheduler;

import org.districompute.distributed.registry.WorkerStatus;

/**
 * A worker's state at the moment of a scheduling decision.
 *
 * @param workerId worker id
 * @param status worker status
 * @param maxSlots current concurrency limit
 * @param assigned attempts currently assigned by the coordinator
 * @param cpuCores processors of the worker's JVM
 * @param maxTaskMemoryBytes memory one attempt may use on the worker
 * @param maxMessageBytes largest frame the worker accepts
 * @param freeMemoryBytes free heap from the latest heartbeat, or {@code -1}
 * @param operationsPerSecond observed kernel throughput for the task's algorithm, or 0 if unknown
 * @param transferBytesPerSecond observed transfer rate, or 0 if unknown
 * @param samples completed attempts behind the observations
 * @param idleNanos how long the worker has had no assigned attempt (0 while it has some)
 * @param lastAssignedNanos monotonic time of its latest assignment, or 0
 */
public record Candidate(String workerId, WorkerStatus status, int maxSlots, int assigned, int cpuCores,
        long maxTaskMemoryBytes, int maxMessageBytes, long freeMemoryBytes, double operationsPerSecond,
        double transferBytesPerSecond, long samples, long idleNanos, long lastAssignedNanos) {

    /**
     * Free slots.
     *
     * @return {@code maxSlots - assigned}, never negative
     */
    public int freeSlots() {
        return Math.max(0, maxSlots - assigned);
    }

    /**
     * Fraction of slots in use.
     *
     * @return {@code assigned / maxSlots}
     */
    public double utilization() {
        return maxSlots == 0 ? 1.0 : (double) assigned / maxSlots;
    }
}
