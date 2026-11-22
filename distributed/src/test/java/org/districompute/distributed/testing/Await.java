package org.districompute.distributed.testing;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition until it holds or a deadline passes. */
public final class Await {

    private Await() {
    }

    /**
     * Waits for a condition.
     *
     * @param timeout how long to wait
     * @param condition the condition
     * @param description what is awaited, for the failure message
     */
    public static void until(Duration timeout, BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out after " + timeout.toMillis() + " ms waiting for " + description);
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, e);
            }
        }
    }
}
