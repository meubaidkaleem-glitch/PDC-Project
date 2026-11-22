package org.districompute.distributed.task;

import java.util.List;
import org.districompute.core.partition.PartitionStatus;

/**
 * Immutable view of a partition.
 *
 * @param index partition index
 * @param partitionId Core partition id
 * @param status Core partition status
 * @param attempts its attempts, oldest first
 */
public record PartitionSnapshot(int index, String partitionId, PartitionStatus status,
        List<AttemptSnapshot> attempts) {

    /** Copies the list. */
    public PartitionSnapshot {
        attempts = List.copyOf(attempts);
    }
}
