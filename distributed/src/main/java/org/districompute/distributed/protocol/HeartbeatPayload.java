package org.districompute.distributed.protocol;

import java.util.List;

/**
 * {@code HEARTBEAT}: the worker is alive, and this is its load. {@code runningAttempts} lets the coordinator notice
 * an attempt the worker no longer has.
 *
 * @param sequence increasing per connection, starting at 1
 * @param activeTasks attempts currently executing
 * @param maxConcurrentTasks the worker's current limit (it may change at run time)
 * @param freeMemoryBytes free heap of the worker's JVM
 * @param systemLoadAverage the machine's one-minute load average, or {@code -1} if unavailable
 * @param processCpuLoad CPU load of the worker's process in [0, 1], or {@code -1} if unavailable
 * @param completedTasks attempts completed since the worker started
 * @param failedTasks attempts failed since the worker started
 * @param runningAttempts ids of the attempts currently admitted (at most 4096)
 */
public record HeartbeatPayload(
        long sequence,
        int activeTasks,
        int maxConcurrentTasks,
        long freeMemoryBytes,
        double systemLoadAverage,
        double processCpuLoad,
        long completedTasks,
        long failedTasks,
        List<String> runningAttempts) implements Payload {

    /** Validates the components. */
    public HeartbeatPayload {
        if (sequence < 1 || activeTasks < 0) {
            throw new IllegalArgumentException("Need sequence >= 1 and activeTasks >= 0");
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > WorkerCapabilities.MAX_CONCURRENT_TASKS) {
            throw new IllegalArgumentException("maxConcurrentTasks must be between 1 and "
                    + WorkerCapabilities.MAX_CONCURRENT_TASKS);
        }
        Checks.nonNegative(completedTasks, "completedTasks");
        Checks.nonNegative(failedTasks, "failedTasks");
        runningAttempts = Checks.boundedCopy(runningAttempts, "runningAttempts", 4096);
    }
}
