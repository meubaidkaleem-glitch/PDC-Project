package org.districompute.core.execution;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.districompute.core.task.TaskStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns kernel progress calls and status changes of one task into throttled, monotonic {@link ProgressEvent}s.
 *
 * <p>{@link #advance} is lock-free on the hot path. It takes the emission lock only when the throttling interval has
 * elapsed. Status changes, partition completions and completion are always emitted. Emission is serialized, so the
 * listener sees events in sequence order and never concurrently.
 */
final class TaskProgress implements ProgressReporter {

    private static final Logger LOG = LoggerFactory.getLogger(TaskProgress.class);

    private final String taskId;
    private final ProgressListener listener;
    private final long minIntervalNanos;
    private final Clock clock;
    private final AtomicLong completed = new AtomicLong();
    private volatile long lastEmitNanos;
    private long sequence;
    private TaskStatus status = TaskStatus.CREATED;
    private long total = -1;
    private String unit = "";
    private boolean totalFixedByEngine;
    private int completedPartitions;
    private int totalPartitions;
    private long lastEmittedUnits;
    private boolean listenerFailed;

    TaskProgress(String taskId, ProgressListener listener, long minIntervalMillis, Clock clock) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.minIntervalNanos = minIntervalMillis * 1_000_000L;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastEmitNanos = System.nanoTime();
    }

    /** Kernel call: declares the total, unless the engine already fixed it from the partition plan. */
    @Override
    public synchronized void start(long totalUnits, String unitName) {
        if (!totalFixedByEngine) {
            this.total = Math.max(0, totalUnits);
            this.unit = unitName == null ? "" : unitName;
        }
    }

    @Override
    public void advance(long units) {
        if (units <= 0) {
            return;
        }
        completed.addAndGet(units);
        if (System.nanoTime() - lastEmitNanos >= minIntervalNanos) {
            emit(false);
        }
    }

    /** Engine call: fixes the total from the partition plan; later kernel {@link #start} calls are ignored. */
    synchronized void declareTotal(long totalUnits, String unitName, int partitions) {
        this.total = Math.max(0, totalUnits);
        this.unit = unitName;
        this.totalFixedByEngine = true;
        this.totalPartitions = partitions;
    }

    synchronized void status(TaskStatus newStatus) {
        this.status = newStatus;
        if (newStatus == TaskStatus.COMPLETED && total >= 0) {
            completed.set(Math.max(completed.get(), total));
            completedPartitions = totalPartitions;
        }
        emit(true);
    }

    synchronized void partitionCompleted() {
        completedPartitions++;
        emit(true);
    }

    private synchronized void emit(boolean force) {
        long units = completed.get();
        if (total >= 0) {
            units = Math.min(units, total);
        }
        units = Math.max(units, lastEmittedUnits);
        if (!force && units == lastEmittedUnits) {
            return;
        }
        lastEmittedUnits = units;
        lastEmitNanos = System.nanoTime();
        ProgressEvent event = new ProgressEvent(taskId, ++sequence, clock.instant(), status, units, total, unit,
                completedPartitions, totalPartitions);
        try {
            listener.onProgress(event);
        } catch (RuntimeException e) {
            if (!listenerFailed) {
                listenerFailed = true;
                LOG.warn("Progress listener failed for task {}; further failures are not logged", taskId, e);
            }
        }
    }
}
