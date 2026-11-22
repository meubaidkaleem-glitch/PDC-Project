package org.districompute.core.partition;

import java.util.Objects;
import org.districompute.core.serialization.EncodedPayload;

/**
 * What the coordinator sends to a worker: the partition descriptor, the attempt number and the encoded work unit.
 * Serialize it with {@code ContractJson}, or send the payload bytes out-of-band.
 *
 * @param descriptor partition descriptor
 * @param attempt attempt number, starting at 1; incremented by the coordinator on each retry
 * @param payload encoded work unit
 */
public record WorkUnitMessage(PartitionDescriptor descriptor, int attempt, EncodedPayload payload) {

    /** Validates the components. */
    public WorkUnitMessage {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(payload, "payload");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
    }
}
