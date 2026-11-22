package org.districompute.distributed.registry;

import java.time.Instant;
import java.util.List;
import org.districompute.distributed.protocol.WorkerCapabilities;

/**
 * Immutable view of a worker for scheduling, monitoring and the Application. Serializes to JSON
 * ({@code docs/integration-contract.md}, "Workers").
 *
 * @param workerId worker id
 * @param status current status
 * @param statusReason why the status last changed
 * @param statusSince when the status last changed
 * @param sessionId current registration, or {@code null} once disconnected
 * @param endpoint the worker's address as seen by the coordinator
 * @param hostname host name reported by the worker
 * @param registeredAt when the current (or last) registration was accepted
 * @param registrations number of accepted registrations of this worker id
 * @param lastHeartbeatAt when the latest heartbeat arrived, or {@code null}
 * @param capabilities capabilities from the latest registration
 * @param maxConcurrentTasks current concurrency limit (from the latest heartbeat)
 * @param assignedTasks attempts assigned by the coordinator and not yet finished
 * @param load load from the latest heartbeat
 * @param counters attempt counters
 * @param performance observed performance per algorithm
 */
public record WorkerSnapshot(
        String workerId,
        WorkerStatus status,
        String statusReason,
        Instant statusSince,
        String sessionId,
        String endpoint,
        String hostname,
        Instant registeredAt,
        long registrations,
        Instant lastHeartbeatAt,
        WorkerCapabilities capabilities,
        int maxConcurrentTasks,
        int assignedTasks,
        WorkerLoad load,
        WorkerCounters counters,
        List<PerformanceStats> performance) {

    /** Copies the list. */
    public WorkerSnapshot {
        performance = List.copyOf(performance);
    }

    /**
     * Free attempt slots.
     *
     * @return {@code maxConcurrentTasks - assignedTasks}, never negative
     */
    public int freeSlots() {
        return Math.max(0, maxConcurrentTasks - assignedTasks);
    }
}
