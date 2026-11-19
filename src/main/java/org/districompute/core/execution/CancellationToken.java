package org.districompute.core.execution;

import java.util.Optional;

/**
 * Read-only view of a cooperative cancellation signal.
 *
 * <p>Kernels call {@link #throwIfCancelled()} at bounded intervals, for example once per sieve segment or per row
 * block. Cancellation is therefore cooperative: its latency is bounded by the work between two checks.
 */
public interface CancellationToken {

    /** A token that is never cancelled. */
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Optional<CancellationReason> reason() {
            return Optional.empty();
        }
    };

    /**
     * Whether cancellation has been requested.
     *
     * @return {@code true} once cancelled
     */
    boolean isCancelled();

    /**
     * The reason for cancellation, if cancelled.
     *
     * @return the first reason given
     */
    Optional<CancellationReason> reason();

    /**
     * Throws if cancellation has been requested.
     *
     * @throws TaskCancelledException if cancelled
     */
    default void throwIfCancelled() {
        if (isCancelled()) {
            throw new TaskCancelledException(reason().orElse(CancellationReason.USER_REQUESTED));
        }
    }
}
