package org.districompute.distributed.protocol;

import java.util.Objects;
import org.districompute.core.partition.PartialResultMessage;

/**
 * {@code RESULT}: the partial result of an attempt. The result is Core's {@code PartialResultMessage}, unchanged;
 * Core validates and classifies it on the coordinator.
 *
 * @param partialResult Core partial result (includes the kernel time {@code computeNanos})
 * @param timing durations measured by the worker
 */
public record ResultPayload(PartialResultMessage partialResult, WorkerTiming timing) implements Payload {

    /** Validates the components. */
    public ResultPayload {
        Objects.requireNonNull(partialResult, "partialResult");
        Objects.requireNonNull(timing, "timing");
    }

    @Override
    public void validateAgainst(Envelope envelope) {
        if (!partialResult.taskId().equals(envelope.taskId())
                || !partialResult.partitionId().equals(envelope.partitionId())
                || partialResult.attempt() != Ids.attemptNumber(envelope.partitionId(), envelope.attemptId())) {
            throw ProtocolException.invalid("The partial result belongs to " + partialResult.partitionId()
                    + " attempt " + partialResult.attempt() + ", not to " + envelope.attemptId(), envelope.messageId());
        }
        if (!partialResult.workerId().equals(envelope.workerId())) {
            throw ProtocolException.invalid("The partial result names worker " + partialResult.workerId()
                    + " but was sent by " + envelope.workerId(), envelope.messageId());
        }
    }
}
