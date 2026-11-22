package org.districompute.distributed.protocol;

/**
 * {@code SHUTDOWN}: the sender is going away.
 *
 * @param reason human-readable reason
 * @param drain from a worker: it finishes its running attempts and sends their results before disconnecting
 * @param reconnect from the coordinator: workers should keep trying to reconnect (for example a restart)
 */
public record ShutdownPayload(String reason, boolean drain, boolean reconnect) implements Payload {

    /** Normalizes the components. */
    public ShutdownPayload {
        reason = Checks.truncate(reason == null || reason.isBlank() ? "shutdown" : reason, 500);
    }
}
