package org.districompute.distributed.task;

import java.util.Objects;
import org.districompute.core.result.TaskResult;

/**
 * The end of a task: Core's result and the runtime's final snapshot (partitions, attempts, metrics).
 *
 * @param result Core's task result
 * @param snapshot the runtime's view at completion
 */
public record TaskOutcome(TaskResult result, TaskSnapshot snapshot) {

    /** Validates the components. */
    public TaskOutcome {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
