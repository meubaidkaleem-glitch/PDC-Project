package org.districompute.core.execution;

/**
 * Receives progress events of one task. The engine never invokes a listener concurrently for the same task, and it
 * delivers events in {@code sequence} order. Calls may arrive on worker threads. Listeners must return quickly and
 * must not block. A listener that throws is logged once and otherwise ignored: it never fails the task.
 */
@FunctionalInterface
public interface ProgressListener {

    /** A listener that ignores all events. */
    ProgressListener NONE = event -> {
    };

    /**
     * Receives one event.
     *
     * @param event the progress event
     */
    void onProgress(ProgressEvent event);
}
