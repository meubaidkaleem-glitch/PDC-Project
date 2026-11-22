package org.districompute.distributed.registry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.districompute.distributed.protocol.WorkerCapabilities;

/**
 * All workers known to the coordinator, connected or not. Records of disconnected workers are kept (bounded) so
 * that a worker that reconnects with the same id keeps its counters and performance history.
 *
 * <p>Not thread-safe: owned by the coordinator's control loop.
 */
public final class WorkerRegistry {

    /** Outcome of a registration request. */
    public sealed interface Registration permits Accepted, Rejected {
    }

    /**
     * The worker is registered.
     *
     * @param record its record
     * @param reconnection whether the id was known before
     */
    public record Accepted(WorkerRecord record, boolean reconnection) implements Registration {
    }

    /**
     * The registration is refused.
     *
     * @param reason explanation sent to the worker
     */
    public record Rejected(String reason) implements Registration {
    }

    private final int maxWorkers;
    private final int maxRetainedDisconnected;
    private final Map<String, WorkerRecord> records = new LinkedHashMap<>();

    /**
     * Creates a registry.
     *
     * @param maxWorkers maximum number of connected workers
     * @param maxRetainedDisconnected records of disconnected workers kept for history
     */
    public WorkerRegistry(int maxWorkers, int maxRetainedDisconnected) {
        if (maxWorkers < 1 || maxRetainedDisconnected < 0) {
            throw new IllegalArgumentException("Need maxWorkers >= 1 and maxRetainedDisconnected >= 0");
        }
        this.maxWorkers = maxWorkers;
        this.maxRetainedDisconnected = maxRetainedDisconnected;
    }

    /**
     * Registers a worker whose proof has been verified.
     *
     * @param workerId worker id
     * @param sessionId new session id
     * @param capabilities reported capabilities
     * @param endpoint remote address
     * @param now current time
     * @param nowNanos current monotonic time
     * @return accepted, or rejected with a reason
     */
    public Registration register(String workerId, String sessionId, WorkerCapabilities capabilities,
            String endpoint, Instant now, long nowNanos) {
        WorkerRecord existing = records.get(workerId);
        if (existing != null && existing.status().isConnected()) {
            return new Rejected("Worker id " + workerId + " is already registered (status " + existing.status()
                    + "); use a unique id per worker process");
        }
        if (connectedCount() >= maxWorkers) {
            return new Rejected("The coordinator accepts at most " + maxWorkers + " workers");
        }
        WorkerRecord record = existing != null ? existing : new WorkerRecord(workerId);
        records.put(workerId, record);
        record.register(sessionId, capabilities, endpoint, now, nowNanos);
        return new Accepted(record, existing != null);
    }

    /**
     * Marks a worker as disconnected and frees its slots.
     *
     * @param workerId worker id
     * @param reason why
     * @param now current time
     * @return the attempt ids that held a slot on the worker (to be recovered)
     */
    public List<String> disconnect(String workerId, String reason, Instant now) {
        WorkerRecord record = records.get(workerId);
        if (record == null || !record.status().isConnected()) {
            return List.of();
        }
        List<String> attempts = new ArrayList<>(record.assignedAttempts());
        record.disconnect(reason, now);
        evictOldDisconnected();
        return attempts;
    }

    /**
     * Looks up a worker.
     *
     * @param workerId worker id
     * @return its record, if known
     */
    public Optional<WorkerRecord> get(String workerId) {
        return Optional.ofNullable(records.get(workerId));
    }

    /**
     * All records, connected or not, in first-registration order.
     *
     * @return the records (a copy of the list)
     */
    public List<WorkerRecord> all() {
        return new ArrayList<>(records.values());
    }

    /**
     * Records of connected workers.
     *
     * @return the records, in first-registration order
     */
    public List<WorkerRecord> connected() {
        return records.values().stream().filter(r -> r.status().isConnected()).toList();
    }

    /**
     * Number of connected workers.
     *
     * @return the count
     */
    public int connectedCount() {
        return (int) records.values().stream().filter(r -> r.status().isConnected()).count();
    }

    /**
     * Snapshots of all workers.
     *
     * @return one snapshot per record
     */
    public List<WorkerSnapshot> snapshots() {
        return records.values().stream().map(WorkerRecord::snapshot).toList();
    }

    private void evictOldDisconnected() {
        List<WorkerRecord> disconnected = records.values().stream().filter(r -> !r.status().isConnected())
                .sorted(Comparator.comparing(r -> r.snapshot().statusSince())).toList();
        for (int i = 0; i < disconnected.size() - maxRetainedDisconnected; i++) {
            records.remove(disconnected.get(i).workerId());
        }
    }
}
