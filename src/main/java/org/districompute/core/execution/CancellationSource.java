package org.districompute.core.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A cancellation signal that can be triggered. It is thread-safe, idempotent, and the first reason wins.
 *
 * <p>Children ({@link #newChild()}) are cancelled with the parent's reason when the parent is cancelled, but can
 * also be cancelled on their own. The engine uses this so that a failing partition can stop its siblings
 * ({@link CancellationReason#FAILURE}) without cancelling the caller's token.
 */
public final class CancellationSource implements CancellationToken {

    private static final Logger LOG = LoggerFactory.getLogger(CancellationSource.class);

    private final AtomicReference<CancellationReason> reason = new AtomicReference<>();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    /**
     * Requests cancellation.
     *
     * @param why reason for cancellation
     * @return {@code true} if this call cancelled the source, {@code false} if it was already cancelled
     */
    public boolean cancel(CancellationReason why) {
        Objects.requireNonNull(why, "why");
        if (!reason.compareAndSet(null, why)) {
            return false;
        }
        for (Runnable callback : callbacks) {
            runSafely(callback);
        }
        return true;
    }

    @Override
    public boolean isCancelled() {
        return reason.get() != null;
    }

    @Override
    public Optional<CancellationReason> reason() {
        return Optional.ofNullable(reason.get());
    }

    @Override
    public void throwIfCancelled() {
        CancellationReason current = reason.get();
        if (current != null) {
            throw new TaskCancelledException(current);
        }
    }

    /**
     * Registers a callback to run once on cancellation. If the source is already cancelled, the callback runs
     * immediately on the calling thread. Callbacks must be fast and must not block.
     *
     * @param callback action to run once
     */
    public void onCancel(Runnable callback) {
        Runnable once = new RunOnce(Objects.requireNonNull(callback, "callback"));
        callbacks.add(once);
        if (isCancelled()) {
            runSafely(once);
        }
    }

    /**
     * Creates a child source that is cancelled when this source is cancelled.
     *
     * @return the child
     */
    public CancellationSource newChild() {
        CancellationSource child = new CancellationSource();
        onCancel(() -> child.cancel(reason.get()));
        return child;
    }

    private static void runSafely(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.warn("Cancellation callback failed", e);
        }
    }

    /** Guards a callback so that it runs at most once, even if cancellation and registration race. */
    private static final class RunOnce implements Runnable {
        private final Runnable delegate;
        private final AtomicBoolean done = new AtomicBoolean();

        RunOnce(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            if (done.compareAndSet(false, true)) {
                delegate.run();
            }
        }
    }
}
