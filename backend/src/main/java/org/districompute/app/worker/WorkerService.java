package org.districompute.app.worker;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.monitoring.ApplicationEvents;
import org.districompute.app.monitoring.RuntimeEventBridge;
import org.districompute.app.persistence.Enums.Severity;
import org.districompute.app.persistence.WorkerEntity;
import org.districompute.app.persistence.WorkerRepository;
import org.districompute.app.security.AuditService;
import org.districompute.app.worker.WorkerViews.ConnectionTest;
import org.districompute.app.worker.WorkerViews.Registration;
import org.districompute.app.worker.WorkerViews.WorkerView;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.registry.WorkerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker management. The coordinator's registry is the truth about connected workers and their reported
 * capabilities; the application keeps a record per worker id (history, approval, deactivation, notes) and drives
 * the coordinator's administration operations ({@code docs/workers.md}).
 */
@Service
public class WorkerService {

    /** Status of a registered worker that has never connected. */
    public static final String NEVER_CONNECTED = "NEVER_CONNECTED";

    private static final Logger LOG = LoggerFactory.getLogger(WorkerService.class);
    private static final String APPROVAL_REASON = "awaiting approval by an administrator";

    private final Coordinator coordinator;
    private final WorkerRepository workers;
    private final AppProperties properties;
    private final JsonSupport json;
    private final AuditService audit;
    private final ApplicationEvents events;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final RuntimeEventBridge bridge;
    private final Clock clock;

    WorkerService(Coordinator coordinator, WorkerRepository workers, AppProperties properties, JsonSupport json,
            AuditService audit, ApplicationEvents events, TransactionTemplate tx,
            @Qualifier("executionExecutor") Executor executor, RuntimeEventBridge bridge, Clock clock) {
        this.coordinator = coordinator;
        this.workers = workers;
        this.properties = properties;
        this.json = json;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.executor = executor;
        this.bridge = bridge;
        this.clock = clock;
    }

    /** Follows worker events, so records update as soon as a worker registers or leaves. */
    @EventListener(ApplicationReadyEvent.class)
    public void follow() {
        bridge.onWorkerEvent(e -> {
            if (e.workerId() != null) {
                executor.execute(() -> sync(e.workerId()));
            }
        });
        syncAll();
    }

    /** Refreshes every record from the coordinator (heartbeats and counters change without events). */
    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void syncAll() {
        for (WorkerSnapshot snapshot : coordinator.workers()) {
            try {
                sync(snapshot);
            } catch (RuntimeException e) {
                LOG.warn("Could not record worker {}", snapshot.workerId(), e);
            }
        }
    }

    void sync(String workerId) {
        coordinator.worker(workerId).ifPresent(this::sync);
    }

    private void sync(WorkerSnapshot s) {
        boolean deactivateForApproval = Boolean.TRUE.equals(tx.execute(status -> {
            Instant now = clock.instant();
            WorkerEntity entity = workers.findById(s.workerId()).orElseGet(() -> new WorkerEntity(s.workerId(),
                    s.status().name(), now));
            boolean first = entity.getRegisteredAt() == null && s.registeredAt() != null;
            if (first) {
                entity.setRegisteredAt(s.registeredAt());
            }
            entity.setHostname(s.hostname());
            entity.setEndpoint(s.endpoint());
            if (s.capabilities() != null) {
                entity.setCapabilities(json.write(s.capabilities()));
            }
            entity.setConnectionStatus(s.status().name());
            entity.setStatusReason(s.statusReason() == null ? null : truncate(s.statusReason(), 500));
            entity.setLastHeartbeatAt(s.lastHeartbeatAt());
            if (s.status().isConnected()) {
                entity.setLastSeenAt(now);
            }
            entity.setCompletedTasks(s.counters().succeeded());
            entity.setFailedTasks(s.counters().failed() + s.counters().timedOut() + s.counters().lost()
                    + s.counters().invalidResults());
            entity.setUpdatedAt(now);
            workers.save(entity);
            if (first) {
                events.publish(null, null, "WORKER_RECORDED", Severity.INFO, "Worker " + s.workerId()
                        + " registered for the first time from " + s.endpoint(), Map.of("workerId", s.workerId()));
            }
            return properties.workers().requireApproval() && !entity.isApproved() && !entity.isDeactivated()
                    && s.status() == WorkerStatus.ACTIVE;
        }));
        if (deactivateForApproval) {
            coordinator.deactivateWorker(s.workerId(), APPROVAL_REASON);
        }
    }

    /**
     * Every known worker: connected ones and the application's records.
     *
     * @return workers, connected first
     */
    @Transactional(readOnly = true)
    public List<WorkerView> list() {
        Map<String, WorkerSnapshot> live = new LinkedHashMap<>();
        coordinator.workers().forEach(s -> live.put(s.workerId(), s));
        Map<String, WorkerEntity> records = new LinkedHashMap<>();
        workers.findAll().forEach(w -> records.put(w.getWorkerId(), w));
        List<WorkerView> out = new ArrayList<>();
        live.forEach((id, s) -> out.add(view(s, records.get(id))));
        records.forEach((id, r) -> {
            if (!live.containsKey(id)) {
                out.add(view(null, r));
            }
        });
        out.sort(Comparator.comparing((WorkerView w) -> !w.connected()).thenComparing(WorkerView::workerId));
        return out;
    }

    /**
     * One worker.
     *
     * @param workerId worker id
     * @return the worker
     */
    @Transactional(readOnly = true)
    public WorkerView get(String workerId) {
        Optional<WorkerSnapshot> live = coordinator.worker(workerId);
        Optional<WorkerEntity> record = workers.findById(workerId);
        if (live.isEmpty() && record.isEmpty()) {
            throw ApiException.notFound("Worker", workerId);
        }
        return view(live.orElse(null), record.orElse(null));
    }

    /**
     * Registers an expected worker (approved) and returns how to start it.
     *
     * @param workerId worker id
     * @param notes notes
     * @param username administrator
     * @return start instructions
     */
    @Transactional
    public Registration register(String workerId, String notes, String username) {
        Instant now = clock.instant();
        WorkerEntity entity = workers.findById(workerId).orElseGet(() -> new WorkerEntity(workerId, NEVER_CONNECTED,
                now));
        entity.setApproved(true);
        entity.setApprovedBy(username);
        entity.setApprovedAt(now);
        if (notes != null) {
            entity.setNotes(notes);
        }
        entity.setUpdatedAt(now);
        workers.save(entity);
        audit.record(username, "WORKER_REGISTERED", "WORKER", workerId, notes, null);
        coordinator.worker(workerId).filter(w -> w.status() == WorkerStatus.DEACTIVATED
                && APPROVAL_REASON.equals(reasonAfterPrefix(w.statusReason()))).ifPresent(w -> {
                    if (!entity.isDeactivated()) {
                        coordinator.activateWorker(workerId);
                    }
                });
        return registration(workerId);
    }

    /**
     * Start instructions for a worker.
     *
     * @param workerId worker id
     * @return the instructions
     */
    public Registration registration(String workerId) {
        AppProperties.Coordinator c = properties.coordinator();
        boolean tls = c.tlsKeystore() != null;
        String host = c.advertisedHost();
        int port = coordinator.port();
        String command = "java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator " + host
                + ":" + port + " --id " + workerId + " --secret-file cluster.secret"
                + (tls ? " --tls-truststore truststore.p12 --tls-truststore-password <password>" : "");
        String config = "coordinator=" + host + ":" + port + "\nid=" + workerId + "\nsecret-file=cluster.secret\n"
                + "# max-concurrent-tasks=4\n" + (tls ? "tls-truststore=truststore.p12\n" : "");
        String secret = c.secretFile() != null ? "Copy the cluster secret file " + c.secretFile().getFileName()
                + " from the application server (owner-only permissions) or set DISTRICOMPUTE_SECRET."
                : "The application uses DISTRICOMPUTE_SECRET or the generated file data/cluster.secret on the "
                        + "application server; give the worker the same secret through a secure channel.";
        return new Registration(workerId, host, port, tls, properties.workers().requireApproval(), command, config,
                secret);
    }

    /**
     * Approves a worker (relevant when approval is required).
     *
     * @param workerId worker id
     * @param username administrator
     * @return the worker
     */
    @Transactional
    public WorkerView approve(String workerId, String username) {
        register(workerId, null, username);
        return get(workerId);
    }

    /**
     * Stops scheduling on a worker until it is activated again.
     *
     * @param workerId worker id
     * @param reason reason
     * @param username administrator
     * @return the worker
     */
    @Transactional
    public WorkerView deactivate(String workerId, String reason, String username) {
        String why = reason == null || reason.isBlank() ? "deactivated by " + username : reason;
        boolean known = coordinator.deactivateWorker(workerId, why);
        WorkerEntity entity = record(workerId, known);
        entity.setDeactivated(true);
        entity.setUpdatedAt(clock.instant());
        workers.save(entity);
        audit.record(username, "WORKER_DEACTIVATED", "WORKER", workerId, why, null);
        return get(workerId);
    }

    /**
     * Lets a deactivated worker receive work again.
     *
     * @param workerId worker id
     * @param username administrator
     * @return the worker
     */
    @Transactional
    public WorkerView activate(String workerId, String username) {
        WorkerEntity entity = record(workerId, coordinator.worker(workerId).isPresent());
        if (properties.workers().requireApproval() && !entity.isApproved()) {
            throw ApiException.conflict("NOT_APPROVED", "Approve the worker before activating it");
        }
        coordinator.activateWorker(workerId);
        entity.setDeactivated(false);
        entity.setUpdatedAt(clock.instant());
        workers.save(entity);
        audit.record(username, "WORKER_ACTIVATED", "WORKER", workerId, null, null);
        return get(workerId);
    }

    /**
     * Removes a worker: a connected worker is told to stop (its running attempts are retried elsewhere), then the
     * coordinator and the application forget it. Its past events and partitions keep its id.
     *
     * @param workerId worker id
     * @param reason reason
     * @param username administrator
     */
    @Transactional
    public void remove(String workerId, String reason, String username) {
        String why = reason == null || reason.isBlank() ? "removed by " + username : reason;
        boolean known = coordinator.worker(workerId).isPresent() || workers.existsById(workerId);
        if (!known) {
            throw ApiException.notFound("Worker", workerId);
        }
        coordinator.disconnectWorker(workerId, why, true);
        coordinator.forgetWorker(workerId);
        workers.deleteById(workerId);
        audit.record(username, "WORKER_REMOVED", "WORKER", workerId, why, null);
        events.publish(null, null, "WORKER_REMOVED", Severity.WARN, "Worker " + workerId + " removed: " + why,
                Map.of("workerId", workerId));
    }

    /**
     * Tests a worker's connection: whether this server's worker port accepts connections and whether the worker's
     * heartbeats are on time. The coordinator cannot open connections to workers (workers connect to it), so this is
     * what can be verified from here.
     *
     * @param workerId worker id
     * @return the outcome
     */
    public ConnectionTest test(String workerId) {
        long interval = properties.coordinator().heartbeatInterval().toMillis();
        boolean listening = portOpen(coordinator.port());
        Optional<WorkerSnapshot> live = coordinator.worker(workerId);
        if (live.isEmpty()) {
            boolean registered = workers.existsById(workerId);
            if (!registered) {
                throw ApiException.notFound("Worker", workerId);
            }
            return new ConnectionTest(workerId, listening, false, NEVER_CONNECTED, null, interval, NEVER_CONNECTED,
                    "The worker has not connected to this coordinator since it started. Check its coordinator "
                            + "address, the shared secret and the firewall.");
        }
        WorkerSnapshot s = live.get();
        Long age = s.lastHeartbeatAt() == null ? null : Duration.between(s.lastHeartbeatAt(), clock.instant())
                .toMillis();
        String verdict;
        String explanation;
        if (!s.status().isConnected()) {
            verdict = "DISCONNECTED";
            explanation = "The worker is disconnected: " + s.statusReason();
        } else if (age != null && age > 2 * interval) {
            verdict = "LATE";
            explanation = "Connected, but the last heartbeat arrived " + age + " ms ago (expected every " + interval
                    + " ms).";
        } else {
            verdict = "OK";
            explanation = "Connected; the last heartbeat arrived " + age + " ms ago (expected every " + interval
                    + " ms).";
        }
        if (!listening) {
            explanation += " The coordinator's worker port did not accept a test connection from this server.";
        }
        return new ConnectionTest(workerId, listening, s.status().isConnected(), s.status().name(), age, interval,
                verdict, explanation);
    }

    private WorkerEntity record(String workerId, boolean knownToCoordinator) {
        Optional<WorkerEntity> existing = workers.findById(workerId);
        if (existing.isEmpty() && !knownToCoordinator) {
            throw ApiException.notFound("Worker", workerId);
        }
        return existing.orElseGet(() -> new WorkerEntity(workerId, "UNKNOWN", clock.instant()));
    }

    private WorkerView view(WorkerSnapshot s, WorkerEntity r) {
        WorkerCapabilities caps = s != null ? s.capabilities()
                : r != null && r.getCapabilities() != null ? json.read(r.getCapabilities(), WorkerCapabilities.class)
                : null;
        String status = s != null ? s.status().name() : r != null && r.getRegisteredAt() == null ? NEVER_CONNECTED
                : "DISCONNECTED";
        Map<String, Long> counters = new LinkedHashMap<>();
        if (s != null) {
            counters.put("assigned", s.counters().assigned());
            counters.put("succeeded", s.counters().succeeded());
            counters.put("failed", s.counters().failed());
            counters.put("rejected", s.counters().rejected());
            counters.put("timedOut", s.counters().timedOut());
            counters.put("lost", s.counters().lost());
            counters.put("invalidResults", s.counters().invalidResults());
            counters.put("duplicateResults", s.counters().duplicateResults());
        }
        List<Map<String, Object>> performance = s == null ? List.of() : s.performance().stream()
                .map(p -> Map.<String, Object>of("algorithm", p.algorithm().value(), "samples", p.samples(),
                        "operationsPerSecond", p.operationsPerSecond(), "transferBytesPerSecond",
                        p.transferBytesPerSecond(), "meanKernelMillis", p.meanKernelMillis())).toList();
        List<String> gpu = caps == null || caps.gpu() == null ? List.of() : caps.gpu().available()
                ? caps.gpu().backends() : caps.gpu().unavailableReasons();
        return new WorkerView(s != null ? s.workerId() : r.getWorkerId(),
                s != null ? s.hostname() : r.getHostname(), s != null ? s.endpoint() : r.getEndpoint(), status,
                s != null ? s.statusReason() : r.getStatusReason(), s != null && s.status().isConnected(),
                r != null && r.isApproved(), r != null && r.isDeactivated(),
                caps == null ? null : caps.cpuCores(), caps == null ? null : caps.maxHeapBytes(),
                caps == null ? null : caps.totalMemoryBytes(),
                caps == null ? null : caps.osName() + " (" + caps.osArch() + ")",
                caps == null ? null : caps.javaVersion(), caps == null ? null : caps.runtimeVersion(),
                caps == null ? null : caps.coreVersion(), caps != null && caps.gpu() != null && caps.gpu().available(),
                gpu, caps == null ? List.of() : caps.algorithms().stream()
                        .map(a -> a.algorithm().id().value() + "@" + a.algorithm().version()).toList(),
                caps == null ? Map.of() : caps.labels(), caps != null && caps.faultSimulation(),
                s != null ? s.maxConcurrentTasks() : null, s != null ? s.assignedTasks() : null,
                s != null && s.load() != null ? s.load().activeTasks() : null,
                s != null && s.load() != null && s.load().freeMemoryBytes() >= 0 ? s.load().freeMemoryBytes() : null,
                s != null && s.load() != null ? s.load().processCpuLoad() : null,
                s != null && s.load() != null ? s.load().systemLoadAverage() : null,
                s != null ? s.counters().succeeded() : r.getCompletedTasks(),
                s != null ? s.counters().failed() + s.counters().timedOut() + s.counters().lost()
                        + s.counters().invalidResults() : r.getFailedTasks(),
                counters, performance, s != null ? s.lastHeartbeatAt() : r.getLastHeartbeatAt(),
                r != null ? r.getRegisteredAt() : s.registeredAt(), r != null ? r.getLastSeenAt() : null,
                r != null ? r.getNotes() : null);
    }

    private static boolean portOpen(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static String reasonAfterPrefix(String reason) {
        if (reason == null) {
            return null;
        }
        int colon = reason.indexOf(": ");
        return colon < 0 ? reason : reason.substring(colon + 2);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
