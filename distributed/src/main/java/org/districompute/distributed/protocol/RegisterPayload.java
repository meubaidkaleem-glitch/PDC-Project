package org.districompute.distributed.protocol;

import java.util.Objects;

/**
 * {@code REGISTER}: the first message on a worker's connection.
 *
 * @param capabilities detected hardware, limits and supported algorithms
 * @param proof proof of the shared secret
 */
public record RegisterPayload(WorkerCapabilities capabilities, RegistrationProof proof) implements Payload {

    /** Validates the components. */
    public RegisterPayload {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(proof, "proof");
    }
}
