package org.districompute.distributed.scheduler;

import java.util.Objects;
import java.util.Set;

/**
 * One scheduling question: which worker should run this attempt?
 *
 * @param taskId task id
 * @param partitionId partition id
 * @param attempt attempt number
 * @param requirements what the attempt needs
 * @param excludedWorkers workers that already failed this partition; used only if no other worker is eligible
 * @param allowedWorkers if not empty, only these workers may be used (worker limit of the task)
 */
public record SchedulingRequest(String taskId, String partitionId, int attempt, TaskRequirements requirements,
        Set<String> excludedWorkers, Set<String> allowedWorkers) {

    /** Copies the sets. */
    public SchedulingRequest {
        Objects.requireNonNull(requirements, "requirements");
        excludedWorkers = Set.copyOf(excludedWorkers);
        allowedWorkers = Set.copyOf(allowedWorkers);
    }
}
