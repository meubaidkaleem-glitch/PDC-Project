package org.districompute.distributed.protocol;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import org.districompute.core.DistriComputeException;
import org.districompute.distributed.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Encodes envelopes to single-line UTF-8 JSON frames and decodes frames back, classifying every kind of bad input
 * with a {@link ProtocolErrorCode}. Java object serialization is never used.
 */
public final class MessageCodec {

    private static final int MAX_DETAIL = 300;

    private MessageCodec() {
    }

    /**
     * Encodes a message. The result contains no line feed, so it can be framed by one.
     *
     * @param envelope the message
     * @return UTF-8 JSON
     */
    public static byte[] encode(Envelope envelope) {
        return Json.toBytes(envelope);
    }

    /**
     * Decodes and validates one frame.
     *
     * @param frame UTF-8 JSON without the line feed
     * @return the validated message
     * @throws ProtocolException describing what is wrong, with the message id when it could be read
     */
    public static Envelope decode(byte[] frame) {
        JsonNode root;
        try {
            root = Json.readTree(frame);
        } catch (JacksonException e) {
            throw new ProtocolException(ProtocolErrorCode.MALFORMED_MESSAGE,
                    "Not valid JSON: " + detail(e.getOriginalMessage()), null, false);
        }
        if (root == null || !root.isObject()) {
            throw new ProtocolException(ProtocolErrorCode.MALFORMED_MESSAGE, "A message must be a JSON object", null,
                    false);
        }
        String messageId = optionalString(root, "messageId", null);
        String version = optionalString(root, "protocolVersion", messageId);
        if (!ProtocolVersion.isSupported(version)) {
            throw new ProtocolException(ProtocolErrorCode.UNSUPPORTED_PROTOCOL_VERSION, "Protocol version " + version
                    + " is not supported (this runtime speaks " + ProtocolVersion.CURRENT + ")",
                    Ids.matches(Ids.MESSAGE_ID, messageId) ? messageId : null, true);
        }
        MessageType type = type(root, messageId);
        JsonNode payloadNode = root.get("payload");
        if (payloadNode == null || !payloadNode.isObject()) {
            throw ProtocolException.invalid("payload must be a JSON object", messageId);
        }
        Payload payload;
        try {
            payload = Json.treeToValue(payloadNode, type.payloadClass());
        } catch (JacksonException e) {
            throw new ProtocolException(ProtocolErrorCode.INVALID_PAYLOAD,
                    "Invalid " + type + " payload: " + detail(rootCause(e)), messageId, false);
        }
        return new Envelope(version, type, messageId, optionalString(root, "inReplyTo", messageId),
                timestamp(root, messageId), optionalString(root, "workerId", messageId),
                optionalString(root, "experimentId", messageId), optionalString(root, "taskId", messageId),
                optionalString(root, "partitionId", messageId), optionalString(root, "attemptId", messageId), payload);
    }

    private static MessageType type(JsonNode root, String messageId) {
        String name = optionalString(root, "type", messageId);
        if (name == null) {
            throw ProtocolException.invalid("type is required", messageId);
        }
        try {
            return MessageType.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN_MESSAGE_TYPE,
                    "Unknown message type " + detail(name), messageId, false);
        }
    }

    private static Instant timestamp(JsonNode root, String messageId) {
        String text = optionalString(root, "timestamp", messageId);
        if (text == null) {
            throw ProtocolException.invalid("timestamp is required", messageId);
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw ProtocolException.invalid("timestamp must be an ISO-8601 instant", messageId);
        }
    }

    private static String optionalString(JsonNode root, String field, String messageId) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw ProtocolException.invalid(field + " must be a string", messageId);
        }
        return node.stringValue();
    }

    private static String rootCause(JacksonException e) {
        Optional<DistriComputeException> domain = DistriComputeException.findIn(e);
        if (domain.isPresent()) {
            return domain.get().getMessage();
        }
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof IllegalArgumentException || cause instanceof NullPointerException) {
            return cause.getMessage();
        }
        return e.getOriginalMessage();
    }

    private static String detail(String text) {
        return Checks.truncate(String.valueOf(text), MAX_DETAIL);
    }
}
