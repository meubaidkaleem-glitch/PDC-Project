package org.districompute.distributed.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One protocol message: metadata common to all types plus a type-specific payload. Serialized as one JSON object
 * per line ({@code docs/network-protocol.md}).
 *
 * <p>The constructor enforces the envelope rules: supported protocol version, id formats, the payload class of the
 * type, which attempt ids the type requires, and consistency between payload and ids. A constructed envelope is
 * therefore always valid.
 *
 * @param protocolVersion protocol version of the sender
 * @param type message type
 * @param messageId unique id of this message
 * @param inReplyTo id of the message this one answers, or {@code null}
 * @param timestamp when the sender created the message (informational; durations never use it)
 * @param workerId the worker this message comes from or goes to
 * @param experimentId experiment id of the task, or {@code null}
 * @param taskId Core task id, for attempt messages
 * @param partitionId Core partition id, for attempt messages
 * @param attemptId attempt id, for attempt messages
 * @param payload type-specific content
 */
public record Envelope(
        String protocolVersion,
        MessageType type,
        String messageId,
        String inReplyTo,
        Instant timestamp,
        String workerId,
        String experimentId,
        String taskId,
        String partitionId,
        String attemptId,
        Payload payload) {

    /**
     * Creates and validates an envelope.
     *
     * @throws ProtocolException if a rule is violated
     */
    public Envelope(String protocolVersion, MessageType type, String messageId, String inReplyTo, Instant timestamp,
            String workerId, String experimentId, String taskId, String partitionId, String attemptId,
            Payload payload) {
        this.protocolVersion = protocolVersion;
        this.type = type;
        this.messageId = messageId;
        this.inReplyTo = inReplyTo;
        this.timestamp = timestamp;
        this.workerId = workerId;
        this.experimentId = experimentId;
        this.taskId = taskId;
        this.partitionId = partitionId;
        this.attemptId = attemptId;
        this.payload = payload;
        validate();
    }

    /**
     * Starts building a message with a fresh message id, the current time and the current protocol version.
     *
     * @param type message type
     * @return the builder
     */
    public static Builder builder(MessageType type) {
        return new Builder(type);
    }

    /**
     * The attempt ids, for attempt messages.
     *
     * @return the ids, or {@code null} if this message concerns no attempt
     */
    public AttemptRef attemptRef() {
        return attemptId == null ? null : new AttemptRef(experimentId, taskId, partitionId, attemptId);
    }

    /**
     * The payload as its concrete class.
     *
     * @param payloadType expected class
     * @param <T> expected class
     * @return the payload
     * @throws ClassCastException if the payload has another class
     */
    public <T extends Payload> T payload(Class<T> payloadType) {
        return payloadType.cast(payload);
    }

    private void validate() {
        if (!ProtocolVersion.isSupported(protocolVersion)) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "Protocol version "
                    + protocolVersion + " is not supported (this runtime speaks " + ProtocolVersion.CURRENT + ")",
                    validId(messageId), true);
        }
        if (!Ids.matches(Ids.MESSAGE_ID, messageId)) {
            throw ProtocolException.invalid("messageId must be 1-64 characters [A-Za-z0-9._:-]", null);
        }
        if (type == null) {
            throw ProtocolException.invalid("type is required", messageId);
        }
        if (timestamp == null) {
            throw ProtocolException.invalid("timestamp is required", messageId);
        }
        if (inReplyTo != null && !Ids.matches(Ids.MESSAGE_ID, inReplyTo)) {
            throw ProtocolException.invalid("inReplyTo is not a valid message id", messageId);
        }
        if (workerId != null && !Ids.matches(Ids.WORKER_ID, workerId)) {
            throw ProtocolException.invalid("workerId must be 1-64 characters [A-Za-z0-9._:-]", messageId);
        }
        if (experimentId != null && !Ids.matches(Ids.TASK_ID, experimentId)) {
            throw ProtocolException.invalid("experimentId must be 1-128 characters [A-Za-z0-9._:-]", messageId);
        }
        if (payload == null || !type.payloadClass().isInstance(payload)) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PAYLOAD, "A " + type + " message needs a "
                    + type.payloadClass().getSimpleName(), messageId, false);
        }
        validateScope();
        payload.validateAgainst(this);
    }

    private void validateScope() {
        boolean anyAttemptId = taskId != null || partitionId != null || attemptId != null;
        switch (type.scope()) {
            case CONNECTION -> {
                if (anyAttemptId || experimentId != null) {
                    throw ProtocolException.invalid(type + " concerns the connection and carries no task ids",
                            messageId);
                }
            }
            case ATTEMPT -> validateAttemptIds();
            case OPTIONAL_ATTEMPT -> {
                if (anyAttemptId) {
                    validateAttemptIds();
                } else if (experimentId != null) {
                    throw ProtocolException.invalid("experimentId without attempt ids", messageId);
                }
            }
            default -> throw new IllegalStateException("Unknown scope " + type.scope());
        }
    }

    private void validateAttemptIds() {
        if (!Ids.matches(Ids.TASK_ID, taskId)) {
            throw ProtocolException.invalid(type + " needs a taskId of 1-128 characters [A-Za-z0-9._:-]", messageId);
        }
        if (!Ids.isPartitionOf(taskId, partitionId)) {
            throw ProtocolException.invalid("partitionId must be <taskId>/p<index>, got " + partitionId, messageId);
        }
        if (Ids.attemptNumber(partitionId, attemptId) < 1) {
            throw ProtocolException.invalid("attemptId must be <partitionId>/a<attempt>, got " + attemptId,
                    messageId);
        }
    }

    private static String validId(String id) {
        return Ids.matches(Ids.MESSAGE_ID, id) ? id : null;
    }

    /** Builds envelopes for sending. */
    public static final class Builder {
        private final MessageType type;
        private String messageId = UUID.randomUUID().toString();
        private String inReplyTo;
        private Instant timestamp = Instant.now();
        private String workerId;
        private String experimentId;
        private String taskId;
        private String partitionId;
        private String attemptId;

        private Builder(MessageType type) {
            this.type = Objects.requireNonNull(type, "type");
        }

        /**
         * Sets the message id (default: a random UUID).
         *
         * @param id message id
         * @return this builder
         */
        public Builder messageId(String id) {
            this.messageId = id;
            return this;
        }

        /**
         * Sets the id of the message being answered.
         *
         * @param id message id, or {@code null}
         * @return this builder
         */
        public Builder inReplyTo(String id) {
            this.inReplyTo = id;
            return this;
        }

        /**
         * Sets the timestamp (default: now).
         *
         * @param instant the timestamp
         * @return this builder
         */
        public Builder timestamp(Instant instant) {
            this.timestamp = instant;
            return this;
        }

        /**
         * Sets the worker id.
         *
         * @param id worker id
         * @return this builder
         */
        public Builder workerId(String id) {
            this.workerId = id;
            return this;
        }

        /**
         * Sets the four attempt ids.
         *
         * @param ref the attempt, or {@code null} for none
         * @return this builder
         */
        public Builder attempt(AttemptRef ref) {
            this.experimentId = ref == null ? null : ref.experimentId();
            this.taskId = ref == null ? null : ref.taskId();
            this.partitionId = ref == null ? null : ref.partitionId();
            this.attemptId = ref == null ? null : ref.attemptId();
            return this;
        }

        /**
         * Builds the envelope.
         *
         * @param payload the payload
         * @return the validated envelope
         * @throws ProtocolException if a rule is violated
         */
        public Envelope payload(Payload payload) {
            return new Envelope(ProtocolVersion.CURRENT, type, messageId, inReplyTo, timestamp, workerId,
                    experimentId, taskId, partitionId, attemptId, payload);
        }
    }
}
