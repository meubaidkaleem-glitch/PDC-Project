package org.districompute.core.execution;

import java.time.Clock;
import java.util.Objects;

/**
 * Configuration of a {@link LocalExecutionEngine}.
 *
 * @param maxThreads upper bound on worker threads per execution (and on the engine's pool size)
 * @param maxConcurrentTasks executions that may run at the same time; further callers wait for a slot
 * @param memoryHeadroom fraction of the currently free heap a single task may plan to use, in {@code (0, 1]}
 * @param progressIntervalMillis minimum interval between progress events of one task (status changes are always
 *     emitted)
 * @param clock clock for status timestamps
 */
public record EngineConfig(int maxThreads, int maxConcurrentTasks, double memoryHeadroom,
        long progressIntervalMillis, Clock clock) {

    /** Validates the configuration. */
    public EngineConfig {
        if (maxThreads < 1) {
            throw new IllegalArgumentException("maxThreads must be at least 1");
        }
        if (maxConcurrentTasks < 1) {
            throw new IllegalArgumentException("maxConcurrentTasks must be at least 1");
        }
        if (!(memoryHeadroom > 0 && memoryHeadroom <= 1)) {
            throw new IllegalArgumentException("memoryHeadroom must be in (0, 1]");
        }
        if (progressIntervalMillis < 0) {
            throw new IllegalArgumentException("progressIntervalMillis must be non-negative");
        }
        Objects.requireNonNull(clock, "clock");
    }

    /**
     * Defaults: one worker thread per available processor, one task at a time, 80% memory headroom, progress at
     * most every 100 ms, and the system UTC clock.
     *
     * @return the default configuration
     */
    public static EngineConfig defaults() {
        return new EngineConfig(Runtime.getRuntime().availableProcessors(), 1, 0.8, 100, Clock.systemUTC());
    }

    /**
     * Returns a copy with a different thread limit.
     *
     * @param threads maximum worker threads
     * @return the new configuration
     */
    public EngineConfig withMaxThreads(int threads) {
        return new EngineConfig(threads, maxConcurrentTasks, memoryHeadroom, progressIntervalMillis, clock);
    }

    /**
     * Returns a copy with a different concurrency limit.
     *
     * @param tasks maximum concurrent executions
     * @return the new configuration
     */
    public EngineConfig withMaxConcurrentTasks(int tasks) {
        return new EngineConfig(maxThreads, tasks, memoryHeadroom, progressIntervalMillis, clock);
    }

    /**
     * Returns a copy with a different progress interval.
     *
     * @param millis minimum interval between progress events
     * @return the new configuration
     */
    public EngineConfig withProgressIntervalMillis(long millis) {
        return new EngineConfig(maxThreads, maxConcurrentTasks, memoryHeadroom, millis, clock);
    }
}
