package org.districompute.distributed.registry;

/**
 * Load reported in the worker's latest heartbeat.
 *
 * @param activeTasks attempts executing on the worker
 * @param freeMemoryBytes free heap of the worker's JVM
 * @param systemLoadAverage one-minute load average, or {@code -1}
 * @param processCpuLoad CPU load of the worker process in [0, 1], or {@code -1}
 * @param completedTasks attempts completed since the worker started
 * @param failedTasks attempts failed since the worker started
 */
public record WorkerLoad(int activeTasks, long freeMemoryBytes, double systemLoadAverage, double processCpuLoad,
        long completedTasks, long failedTasks) {

    /** Load before the first heartbeat. */
    public static final WorkerLoad NONE = new WorkerLoad(0, -1, -1, -1, 0, 0);
}
