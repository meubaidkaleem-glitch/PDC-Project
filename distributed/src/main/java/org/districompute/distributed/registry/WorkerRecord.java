package org.districompute.distributed.registry;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.protocol.WorkerCapabilities;

/**
 * Mutable state of one worker id. Not thread-safe: owned by the coordinator's control loop. Others read
 * {@link #snapshot()}.
 */
public final class WorkerRecord {

    private final String workerId;
    private final PerformanceHistory history = new PerformanceHistory();
    private final Map<AttemptOutcome, Long> outcomes = new EnumMap<>(AttemptOutcome.class);
    private final Set<String> assigned = new LinkedHashSet<>();
    private long assignedTotal;
    private long registrations;
    private String sessionId;
    private String endpoint;
    private WorkerCapabilities capabilities;
    private Instant registeredAt;
    private WorkerStatus status = WorkerStatus.DISCONNECTED;
    private String statusReason = "never registered";
    private Instant statusSince;
    private Instant lastHeartbeatAt;
    private long lastHeartbeatNanos;
    private long lastHeartbeatSequence;
    private WorkerLoad load = WorkerLoad.NONE;
    private int maxConcurrentTasks;
    private long idleSinceNanos;
    private long lastAssignedNanos;
    private int invalidThisRegistration;

    WorkerRecord(String workerId) {
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    void register(String newSessionId, WorkerCapabilities newCapabilities, String newEndpoint, Instant now,
            long nowNanos) {
        this.sessionId = newSessionId;
        this.capabilities = newCapabilities;
        this.endpoint = newEndpoint;
        this.registeredAt = now;
        this.registrations++;
        this.maxConcurrentTasks = newCapabilities.maxConcurrentTasks();
        this.load = WorkerLoad.NONE;
        this.lastHeartbeatAt = now;
        this.lastHeartbeatNanos = nowNanos;
        this.lastHeartbeatSequence = 0;
        this.idleSinceNanos = nowNanos;
        this.assigned.clear();
        this.invalidThisRegistration = 0;
        setStatus(WorkerStatus.ACTIVE, registrations == 1 ? "registered" : "re-registered", now);
    }

    /**
     * Changes the status.
     *
     * @param newStatus the status
     * @param reason why
     * @param now current time
     * @return the previous status, or {@code null} if it did not change
     */
    public WorkerStatus setStatus(WorkerStatus newStatus, String reason, Instant now) {
        if (newStatus == status) {
            return null;
        }
        WorkerStatus previous = status;
        status = newStatus;
        statusReason = reason;
        statusSince = now;
        return previous;
    }

    /**
     * Records a heartbeat. The worker's reported limit replaces the registered one (capacity can change at run
     * time).
     *
     * @param heartbeat the payload
     * @param at arrival time
     * @param atNanos arrival time, monotonic
     * @return {@code false} if the sequence number did not increase (stale or repeated heartbeat)
     */
    public boolean heartbeat(HeartbeatPayload heartbeat, Instant at, long atNanos) {
        if (heartbeat.sequence() <= lastHeartbeatSequence) {
            return false;
        }
        lastHeartbeatSequence = heartbeat.sequence();
        lastHeartbeatAt = at;
        lastHeartbeatNanos = atNanos;
        maxConcurrentTasks = heartbeat.maxConcurrentTasks();
        load = new WorkerLoad(heartbeat.activeTasks(), heartbeat.freeMemoryBytes(), heartbeat.systemLoadAverage(),
                heartbeat.processCpuLoad(), heartbeat.completedTasks(), heartbeat.failedTasks());
        return true;
    }

    /**
     * Records that an attempt occupies one of the worker's slots.
     *
     * @param attemptId the attempt
     * @param nowNanos current monotonic time
     */
    public void assign(String attemptId, long nowNanos) {
        if (assigned.add(attemptId)) {
            assignedTotal++;
            lastAssignedNanos = nowNanos;
        }
    }

    /**
     * Frees the slot of an attempt.
     *
     * @param attemptId the attempt
     * @param nowNanos current monotonic time
     * @return {@code true} if the attempt held a slot
     */
    public boolean release(String attemptId, long nowNanos) {
        boolean removed = assigned.remove(attemptId);
        if (removed && assigned.isEmpty()) {
            idleSinceNanos = nowNanos;
        }
        return removed;
    }

    /**
     * Counts an attempt outcome.
     *
     * @param outcome the outcome
     */
    public void count(AttemptOutcome outcome) {
        outcomes.merge(outcome, 1L, Long::sum);
    }

    /**
     * Counts an invalid or conflicting result for the quarantine rule.
     *
     * @return invalid results since the current registration
     */
    public int noteInvalidResult() {
        count(AttemptOutcome.INVALID_RESULT);
        return ++invalidThisRegistration;
    }

    /**
     * Number of attempts with an outcome.
     *
     * @param outcome the outcome
     * @return the count
     */
    public long outcomes(AttemptOutcome outcome) {
        return get(outcome);
    }

    void disconnect(String reason, Instant now) {
        setStatus(WorkerStatus.DISCONNECTED, reason, now);
        sessionId = null;
        assigned.clear();
    }

    /**
     * The worker id.
     *
     * @return the id
     */
    public String workerId() {
        return workerId;
    }

    /**
     * Current status.
     *
     * @return the status
     */
    public WorkerStatus status() {
        return status;
    }

    /**
     * Current session id.
     *
     * @return the id, or {@code null} once disconnected
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * Capabilities from the latest registration.
     *
     * @return the capabilities, or {@code null} before the first registration
     */
    public WorkerCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Current concurrency limit.
     *
     * @return attempts the worker runs at the same time
     */
    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }

    /**
     * Attempts that hold a slot.
     *
     * @return attempt ids in assignment order (live view; do not modify)
     */
    public Set<String> assignedAttempts() {
        return java.util.Collections.unmodifiableSet(assigned);
    }

    /**
     * Free slots.
     *
     * @return {@code maxConcurrentTasks - assigned}, never negative
     */
    public int freeSlots() {
        return Math.max(0, maxConcurrentTasks - assigned.size());
    }

    /**
     * Monotonic time of the latest heartbeat (or registration).
     *
     * @return {@link System#nanoTime()} value
     */
    public long lastHeartbeatNanos() {
        return lastHeartbeatNanos;
    }

    /**
     * Monotonic time since which the worker has had no assigned attempt.
     *
     * @return {@link System#nanoTime()} value (meaningless while attempts are assigned)
     */
    public long idleSinceNanos() {
        return idleSinceNanos;
    }

    /**
     * Monotonic time of the latest assignment.
     *
     * @return {@link System#nanoTime()} value, or 0 if never assigned
     */
    public long lastAssignedNanos() {
        return lastAssignedNanos;
    }

    /**
     * Observed performance.
     *
     * @return the history (owned by this record)
     */
    public PerformanceHistory history() {
        return history;
    }

    /**
     * Load from the latest heartbeat.
     *
     * @return the load
     */
    public WorkerLoad load() {
        return load;
    }

    /**
     * Immutable view of the current state.
     *
     * @return the snapshot
     */
    public WorkerSnapshot snapshot() {
        WorkerCounters counters = new WorkerCounters(assignedTotal, get(AttemptOutcome.SUCCEEDED),
                get(AttemptOutcome.FAILED), get(AttemptOutcome.REJECTED), get(AttemptOutcome.TIMED_OUT),
                get(AttemptOutcome.LOST), get(AttemptOutcome.INVALID_RESULT), get(AttemptOutcome.DUPLICATE_RESULT));
        List<PerformanceStats> performance = history.all();
        return new WorkerSnapshot(workerId, status, statusReason, statusSince, sessionId, endpoint,
                capabilities == null ? null : capabilities.hostname(), registeredAt, registrations, lastHeartbeatAt,
                capabilities, maxConcurrentTasks, assigned.size(), load, counters, performance);
    }

    private long get(AttemptOutcome outcome) {
        return outcomes.getOrDefault(outcome, 0L);
    }
}
