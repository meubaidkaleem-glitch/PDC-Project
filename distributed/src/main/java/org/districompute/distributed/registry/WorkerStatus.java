package org.districompute.distributed.registry;

/** Status of a worker as the coordinator sees it. Only {@link #ACTIVE} workers receive new attempts. */
public enum WorkerStatus {
    /** Registered and sending heartbeats on time. */
    ACTIVE,
    /** Heartbeats are late; no new attempts until the next heartbeat arrives. */
    SUSPECT,
    /** The worker announced a graceful shutdown; its running attempts may finish, no new ones are sent. */
    DRAINING,
    /** Excluded after repeated invalid results; connected, but receives no attempts. */
    QUARANTINED,
    /**
     * Deactivated by an operator ({@code Coordinator.deactivateWorker}); connected, its running attempts finish,
     * but it receives no new ones. The deactivation survives reconnection until the worker is activated again.
     */
    DEACTIVATED,
    /** The connection is closed or the heartbeat timeout expired. The record is kept for history. */
    DISCONNECTED;

    /**
     * Whether the worker has a live registration.
     *
     * @return {@code false} only for {@link #DISCONNECTED}
     */
    public boolean isConnected() {
        return this != DISCONNECTED;
    }
}
