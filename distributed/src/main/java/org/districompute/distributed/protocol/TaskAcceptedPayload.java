package org.districompute.distributed.protocol;

/**
 * {@code TASK_ACCEPTED}: the worker admitted the attempt.
 *
 * @param activeTasks attempts running on the worker, including this one
 * @param maxConcurrentTasks the worker's current limit
 */
public record TaskAcceptedPayload(int activeTasks, int maxConcurrentTasks) implements Payload {

    /** Validates the components. */
    public TaskAcceptedPayload {
        if (activeTasks < 0 || maxConcurrentTasks < 1) {
            throw new IllegalArgumentException("Need activeTasks >= 0 and maxConcurrentTasks >= 1");
        }
    }
}
