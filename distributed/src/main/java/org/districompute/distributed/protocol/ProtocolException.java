package org.districompute.distributed.protocol;

import java.util.Objects;

/**
 * A message violates the protocol. The receiver answers with an {@code ERROR} message carrying {@link #code()}
 * and, if {@link #fatal()}, closes the connection.
 */
public final class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ProtocolErrorCode code;
    private final String messageId;
    private final boolean fatal;

    /**
     * Creates the exception.
     *
     * @param code the error code
     * @param message what is wrong, safe to send to the peer
     * @param messageId id of the offending message, or {@code null} if it could not be read
     * @param fatal whether the connection must be closed
     */
    public ProtocolException(ProtocolErrorCode code, String message, String messageId, boolean fatal) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.messageId = messageId;
        this.fatal = fatal;
    }

    /**
     * Creates a non-fatal {@link ProtocolErrorCode#INVALID_MESSAGE}.
     *
     * @param message what is wrong
     * @param messageId id of the offending message, possibly {@code null}
     * @return the exception
     */
    public static ProtocolException invalid(String message, String messageId) {
        return new ProtocolException(ProtocolErrorCode.INVALID_MESSAGE, message, messageId, false);
    }

    /**
     * The error code.
     *
     * @return the code
     */
    public ProtocolErrorCode code() {
        return code;
    }

    /**
     * Id of the offending message.
     *
     * @return the id, or {@code null} if unknown
     */
    public String messageId() {
        return messageId;
    }

    /**
     * Whether the connection must be closed.
     *
     * @return {@code true} if fatal
     */
    public boolean fatal() {
        return fatal;
    }
}
