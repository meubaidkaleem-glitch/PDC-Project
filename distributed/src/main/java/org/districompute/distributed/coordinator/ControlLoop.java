package org.districompute.distributed.coordinator;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The coordinator's single state-owning thread. Every change to the registry, tasks and attempts runs here, in
 * the order the commands were posted, so scheduling decisions never race with results or disconnections.
 * Commands must be short; heavy Core work runs elsewhere and posts its outcome back.
 */
final class ControlLoop implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ControlLoop.class);

    private final BlockingQueue<Runnable> queue;
    private final Thread thread;
    private volatile boolean running = true;

    ControlLoop(String name, int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        this.thread = Thread.ofPlatform().name(name).daemon(true).start(this::run);
    }

    /**
     * Posts a command. Blocks while the queue is full, which slows the posting connection down instead of
     * buffering without limit.
     *
     * @param command the command
     * @return {@code false} if the loop is stopped
     */
    boolean execute(Runnable command) {
        if (!running) {
            return false;
        }
        if (inLoop()) {
            // A command posted from the loop itself runs after the current one, never re-entrantly.
            return queue.offer(command);
        }
        try {
            queue.put(command);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs a function on the loop and waits for its result. Runs directly when called from the loop.
     *
     * @param function the function
     * @param timeout how long to wait
     * @param <T> result type
     * @return the result
     * @throws IllegalStateException if the loop is stopped, the wait times out or the function fails
     */
    <T> T call(Callable<T> function, Duration timeout) {
        if (inLoop()) {
            try {
                return function.call();
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        boolean posted = execute(() -> {
            try {
                future.complete(function.call());
            } catch (Exception | Error e) {
                future.completeExceptionally(e);
            }
        });
        if (!posted) {
            throw new IllegalStateException("The coordinator is stopped");
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the coordinator", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(String.valueOf(cause.getMessage()), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("The coordinator did not answer within " + timeout.toMillis() + " ms", e);
        }
    }

    /**
     * Whether the caller runs on the loop thread.
     *
     * @return {@code true} on the loop thread
     */
    boolean inLoop() {
        return Thread.currentThread() == thread;
    }

    /**
     * Stops after the commands already queued.
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        if (!queue.offer(() -> {
        })) {
            // The queue is full, so the loop is busy; it stops by itself once the queue is drained.
            LOG.debug("Control loop queue full at shutdown; it stops after draining");
        }
        if (!inLoop()) {
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            Runnable command;
            try {
                command = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                command.run();
            } catch (RuntimeException | AssertionError e) {
                // A failing command must never stop the coordinator.
                LOG.error("Control loop command failed", e);
            }
        }
    }
}
