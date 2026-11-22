package org.districompute.core.partition;

import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Serializable description of one partition (integration contract, section 6). Partition ids are deterministic
 * ({@code <taskId>/p<index>}), so a regenerated plan yields the same ids.
 *
 * @param schemaVersion contract schema version
 * @param partitionId unique id: {@code <taskId>/p<index>}
 * @param taskId parent task id
 * @param algorithm algorithm and exact version
 * @param index position in the plan, {@code 0 <= index < count}
 * @param count number of partitions in the plan
 * @param bounds input boundaries
 * @param resultType expected partial result type, for example {@code "matrix-block"}
 * @param retryable whether recomputing is safe (deterministic and free of side effects), which makes the
 *     partition eligible for re-execution after a failure
 */
public record PartitionDescriptor(
        String schemaVersion,
        String partitionId,
        String taskId,
        AlgorithmRef algorithm,
        int index,
        int count,
        PartitionBounds bounds,
        String resultType,
        boolean retryable) {

    /** Validates the components. */
    public PartitionDescriptor {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(resultType, "resultType");
        if (count < 1 || index < 0 || index >= count) {
            throw new IllegalArgumentException("Partition index " + index + " outside [0, " + count + ")");
        }
        if (!idOf(taskId, index).equals(partitionId)) {
            throw new IllegalArgumentException("partitionId must be " + idOf(taskId, index) + ", got " + partitionId);
        }
    }

    /**
     * Builds the deterministic partition id.
     *
     * @param taskId parent task id
     * @param index partition index
     * @return {@code <taskId>/p<index>}
     */
    public static String idOf(String taskId, int index) {
        return taskId + "/p" + index;
    }
}
