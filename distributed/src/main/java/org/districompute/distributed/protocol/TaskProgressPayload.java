package org.districompute.distributed.protocol;

/**
 * {@code TASK_PROGRESS}: kernel progress of a running attempt, in the algorithm's work unit. Workers throttle
 * these messages.
 *
 * @param completedUnits units done so far
 * @param totalUnits total units of the partition (0 if unknown)
 * @param unit name of the unit, for example {@code "rows"}
 */
public record TaskProgressPayload(long completedUnits, long totalUnits, String unit) implements Payload {

    /** Validates the components. */
    public TaskProgressPayload {
        Checks.nonNegative(completedUnits, "completedUnits");
        Checks.nonNegative(totalUnits, "totalUnits");
        Checks.optionalText(unit, "unit", 32);
    }
}
