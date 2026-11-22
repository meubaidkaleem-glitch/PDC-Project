package org.districompute.distributed.protocol;

import java.util.Objects;
import org.districompute.core.ErrorInfo;

/**
 * {@code ERROR}: something went wrong. Messages are short and never contain stack traces or internal details.
 *
 * @param code what kind of error
 * @param message human-readable explanation (truncated to 2000 characters)
 * @param retryable whether repeating the same request could succeed
 * @param fatal whether the sender closes the connection after this message
 * @param taskError Core's error for {@link ProtocolErrorCode#TASK_FAILED}, otherwise usually {@code null}
 */
public record ErrorPayload(ProtocolErrorCode code, String message, boolean retryable, boolean fatal,
        ErrorInfo taskError) implements Payload {

    /** Maximum length of {@link #message()}. */
    public static final int MAX_MESSAGE_LENGTH = 2000;

    /** Validates and normalizes the components. */
    public ErrorPayload {
        Objects.requireNonNull(code, "code");
        message = Checks.truncate(message == null || message.isBlank() ? code.name() : message, MAX_MESSAGE_LENGTH);
    }
}
