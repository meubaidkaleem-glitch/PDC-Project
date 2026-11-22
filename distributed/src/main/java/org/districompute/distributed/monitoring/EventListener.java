package org.districompute.distributed.monitoring;

/** Receives runtime events, in sequence order, on a thread of its own subscription. */
@FunctionalInterface
public interface EventListener {

    /**
     * Handles an event. Exceptions are logged and do not affect the runtime or other subscribers.
     *
     * @param event the event
     */
    void onEvent(RuntimeEvent event);
}
