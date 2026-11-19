package org.districompute.core.execution;

import java.util.Objects;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * Thrown by kernels and executors when they observe cancellation. A deadline maps to {@link ErrorCode#TIMEOUT}.
 * Every other reason maps to {@link ErrorCode#CANCELLED}.
 */
public final class TaskCancelledException extends DistriComputeException {

    private static final long serialVersionUID = 1L;

    private final CancellationReason reason;

    /**
     * Creates the exception.
     *
     * @param reason why the execution was cancelled
     */
    public TaskCancelledException(CancellationReason reason) {
        super(reason == CancellationReason.TIMEOUT ? ErrorCode.TIMEOUT : ErrorCode.CANCELLED,
                "Execution cancelled: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    /**
     * Returns why the execution was cancelled.
     *
     * @return the reason
     */
    public CancellationReason reason() {
        return reason;
    }
}
