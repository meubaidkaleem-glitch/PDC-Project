package org.districompute.core.execution;

/**
 * Services available to algorithm code while it runs: cooperative cancellation and progress reporting. The engine
 * supplies the context. Algorithms must not create threads of their own. Parallelism comes from the engine
 * executing partitions.
 */
public interface ExecutionContext {

    /**
     * Returns the cancellation signal to poll at bounded intervals.
     *
     * @return the cancellation token
     */
    CancellationToken cancellation();

    /**
     * Returns the progress sink.
     *
     * @return the progress reporter
     */
    ProgressReporter progress();

    /**
     * Creates a context from its parts.
     *
     * @param cancellation cancellation token
     * @param progress progress reporter
     * @return the context
     */
    static ExecutionContext of(CancellationToken cancellation, ProgressReporter progress) {
        return new BasicExecutionContext(cancellation, progress);
    }

    /**
     * A context that is never cancelled and discards progress. Useful when calling algorithms directly as a
     * library, and in tests.
     *
     * @return the context
     */
    static ExecutionContext unmanaged() {
        return BasicExecutionContext.UNMANAGED;
    }
}
