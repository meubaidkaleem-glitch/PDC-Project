package org.districompute.distributed.scheduler;

import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.task.TaskPriority;

/**
 * What one attempt needs, estimated from Core's workload profile divided by the number of partitions. The
 * estimates are approximate; workers re-check the real work unit.
 *
 * @param algorithm algorithm and exact version resolved by the coordinator's Core
 * @param operationsPerPartition estimated operations of one partition (Core's operation unit)
 * @param payloadBytes estimated size of one encoded work unit
 * @param memoryBytes estimated peak memory of one attempt
 * @param priority task priority
 */
public record TaskRequirements(AlgorithmRef algorithm, double operationsPerPartition, long payloadBytes,
        long memoryBytes, TaskPriority priority) {

    /** Validates the components. */
    public TaskRequirements {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(algorithm.version(), "algorithm.version");
        Objects.requireNonNull(priority, "priority");
    }
}
