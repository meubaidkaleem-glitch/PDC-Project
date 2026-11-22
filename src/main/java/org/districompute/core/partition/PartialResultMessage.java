package org.districompute.core.partition;

import java.util.Objects;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.serialization.SchemaVersion;

/**
 * What a worker returns to the coordinator (integration contract, section 7).
 *
 * @param schemaVersion contract schema version
 * @param taskId parent task id
 * @param partitionId partition id, {@code <taskId>/p<index>}
 * @param index partition index
 * @param attempt attempt number copied from the work unit
 * @param resultType partial-result type, for example {@code "matrix-block"}
 * @param payload encoded partial result
 * @param computeNanos time the worker spent in the partition kernel
 * @param workerId identifier of the worker, assigned by the distributed runtime
 */
public record PartialResultMessage(
        String schemaVersion,
        String taskId,
        String partitionId,
        int index,
        int attempt,
        String resultType,
        EncodedPayload payload,
        long computeNanos,
        String workerId) {

    /** Validates the components. */
    public PartialResultMessage {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(partitionId, "partitionId");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(workerId, "workerId");
    }
}
