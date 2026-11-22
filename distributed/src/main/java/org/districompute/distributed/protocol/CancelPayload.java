package org.districompute.distributed.protocol;

import java.util.Objects;

/**
 * {@code CANCEL}: stop an attempt. Cancellation is cooperative; the worker confirms with an {@code ERROR}
 * ({@link ProtocolErrorCode#TASK_CANCELLED}) or, if the kernel had already finished, with its {@code RESULT}.
 *
 * @param reason why
 * @param detail optional explanation
 */
public record CancelPayload(CancelReason reason, String detail) implements Payload {

    /** Validates the components. */
    public CancelPayload {
        Objects.requireNonNull(reason, "reason");
        detail = Checks.truncate(detail, 500);
    }
}
