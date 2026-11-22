package org.districompute.distributed.protocol;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Error codes carried by {@code ERROR} messages. Task failures additionally carry Core's {@code ErrorInfo}, whose
 * {@code ErrorCode} says what went wrong inside the computation.
 */
public enum ProtocolErrorCode {
    /** The frame is not a JSON object. */
    MALFORMED_MESSAGE,
    /** The frame exceeds the receiver's size limit. The connection is closed. */
    MESSAGE_TOO_LARGE,
    /** The sender's protocol version is not compatible. The connection is closed. */
    UNSUPPORTED_PROTOCOL_VERSION,
    /** The {@code type} is not one of the protocol's message types. */
    UNKNOWN_MESSAGE_TYPE,
    /** A required envelope field is missing or malformed, or the ids are inconsistent. */
    INVALID_MESSAGE,
    /** The payload does not match the schema of its message type. */
    INVALID_PAYLOAD,
    /** The message is not allowed in this direction or in the connection's current state. */
    UNEXPECTED_MESSAGE,
    /** The message id was already received on this connection; the message is ignored. */
    DUPLICATE_MESSAGE,
    /** A message other than {@code REGISTER} arrived before registration. The connection is closed. */
    NOT_REGISTERED,
    /** The registration proof is invalid, stale or replayed. The connection is closed. */
    AUTHENTICATION_FAILED,
    /** The registration is valid but refused (duplicate worker id, limits, policy). The connection is closed. */
    REGISTRATION_REJECTED,
    /** The worker refuses a task (capacity, unsupported algorithm, invalid work unit, shutting down). */
    TASK_REJECTED,
    /** The task failed during execution; {@code taskError} holds Core's error. */
    TASK_FAILED,
    /** The attempt was cancelled, in reply to {@code CANCEL}. */
    TASK_CANCELLED,
    /** The message refers to an attempt the receiver does not know. */
    UNKNOWN_TASK,
    /** The sender is shutting down. */
    SHUTTING_DOWN,
    /** An unexpected error inside the sender. Details are only in the sender's log. */
    INTERNAL_ERROR,
    /** A code this runtime does not know (sent by a newer peer). */
    @JsonEnumDefaultValue
    UNKNOWN
}
