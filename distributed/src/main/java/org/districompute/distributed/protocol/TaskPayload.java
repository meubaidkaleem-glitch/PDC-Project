package org.districompute.distributed.protocol;

import java.util.Objects;
import org.districompute.core.partition.PartitionDescriptor;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.task.TaskPriority;

/**
 * {@code TASK}: one attempt of one partition. The work unit is Core's {@code WorkUnitMessage}, unchanged.
 *
 * @param workUnit Core work unit (descriptor, attempt number, encoded input)
 * @param timeoutMillis time the coordinator allows for this attempt; the worker cancels the kernel after it
 * @param priority the task's priority, informational for the worker
 */
public record TaskPayload(WorkUnitMessage workUnit, long timeoutMillis, TaskPriority priority) implements Payload {

    /** Validates the components. */
    public TaskPayload {
        Objects.requireNonNull(workUnit, "workUnit");
        if (timeoutMillis < 1) {
            throw new IllegalArgumentException("timeoutMillis must be at least 1");
        }
        priority = priority == null ? TaskPriority.NORMAL : priority;
    }

    @Override
    public void validateAgainst(Envelope envelope) {
        PartitionDescriptor descriptor = workUnit.descriptor();
        if (!descriptor.taskId().equals(envelope.taskId())
                || !descriptor.partitionId().equals(envelope.partitionId())) {
            throw ProtocolException.invalid("The work unit belongs to " + descriptor.partitionId()
                    + ", not to " + envelope.partitionId(), envelope.messageId());
        }
        if (workUnit.attempt() != Ids.attemptNumber(envelope.partitionId(), envelope.attemptId())) {
            throw ProtocolException.invalid("The work unit is attempt " + workUnit.attempt() + " but the attempt id is "
                    + envelope.attemptId(), envelope.messageId());
        }
    }
}
