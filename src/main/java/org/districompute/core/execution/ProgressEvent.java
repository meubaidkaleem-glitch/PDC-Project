package org.districompute.core.execution;

import java.time.Instant;
import java.util.Objects;
import org.districompute.core.task.TaskStatus;

/**
 * Progress of one task (integration contract, section 11). Per task, {@code sequence} strictly increases and
 * {@code completedUnits} never decreases. Units are an estimate of work: a task that ends {@code COMPLETED} always
 * reports {@code completedUnits == totalUnits} in its final event.
 *
 * @param taskId task identifier
 * @param sequence 1, 2, 3, … per task
 * @param timestamp when the event was created
 * @param status task status at that time
 * @param completedUnits work units done so far
 * @param totalUnits total work units, or {@code -1} while unknown
 * @param unit name of the work unit, for example {@code "rows"}; empty while unknown
 * @param completedPartitions partitions finished so far
 * @param totalPartitions partitions planned, or 0 before planning
 */
public record ProgressEvent(String taskId, long sequence, Instant timestamp, TaskStatus status, long completedUnits,
        long totalUnits, String unit, int completedPartitions, int totalPartitions) {

    /** Validates the components. */
    public ProgressEvent {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(unit, "unit");
    }
}
