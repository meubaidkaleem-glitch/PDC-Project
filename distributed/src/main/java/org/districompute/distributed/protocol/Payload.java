package org.districompute.distributed.protocol;

/**
 * Type-specific content of a message. The envelope's {@code type} determines the payload class
 * ({@link MessageType#payloadClass()}), so payloads carry no type field of their own.
 */
public sealed interface Payload permits RegisterPayload, RegisterAckPayload, TaskPayload, TaskAcceptedPayload,
        TaskProgressPayload, ResultPayload, ErrorPayload, HeartbeatPayload, HeartbeatAckPayload, CancelPayload,
        ShutdownPayload {

    /**
     * Checks rules that relate the payload to its envelope, such as matching ids. Called by the envelope's
     * constructor.
     *
     * @param envelope the envelope that carries this payload
     * @throws ProtocolException if the payload contradicts the envelope
     */
    default void validateAgainst(Envelope envelope) {
    }
}
