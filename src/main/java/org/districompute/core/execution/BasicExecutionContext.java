package org.districompute.core.execution;

import java.util.Objects;

/** Straightforward {@link ExecutionContext} implementation. */
record BasicExecutionContext(CancellationToken cancellation, ProgressReporter progress) implements ExecutionContext {

    static final BasicExecutionContext UNMANAGED =
            new BasicExecutionContext(CancellationToken.NONE, ProgressReporter.NONE);

    BasicExecutionContext {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(progress, "progress");
    }
}
