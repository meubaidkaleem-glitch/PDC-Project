package org.districompute.core.resource;

import java.util.Objects;
import org.districompute.core.partition.PartitionStrategyType;

/**
 * Parallelizability of a workload.
 *
 * @param partitionable whether Core can partition this workload (needed for multithreaded and distributed modes)
 * @param strategy partition strategy, or {@code null} if not partitionable
 * @param maxUsefulPartitions upper bound on partitions that still contain work (for example the number of rows)
 * @param dataDependency how partial results depend on each other
 * @param aggregationComplexity cost of combining partial results, for example {@code "O(n·log k)"}
 */
public record ParallelismProfile(
        boolean partitionable,
        PartitionStrategyType strategy,
        long maxUsefulPartitions,
        DataDependency dataDependency,
        String aggregationComplexity) {

    /** Validates the components. */
    public ParallelismProfile {
        Objects.requireNonNull(dataDependency, "dataDependency");
        Objects.requireNonNull(aggregationComplexity, "aggregationComplexity");
        if (partitionable && strategy == null) {
            throw new IllegalArgumentException("A partitionable workload needs a strategy");
        }
        if (maxUsefulPartitions < 1) {
            throw new IllegalArgumentException("maxUsefulPartitions must be at least 1");
        }
    }

    /**
     * Profile of a workload that runs only sequentially.
     *
     * @param dependency why it cannot be partitioned
     * @return the profile
     */
    public static ParallelismProfile sequentialOnly(DataDependency dependency) {
        return new ParallelismProfile(false, null, 1, dependency, "none");
    }
}
