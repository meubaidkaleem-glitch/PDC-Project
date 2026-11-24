package org.districompute.app.faultlab;

import jakarta.annotation.PreDestroy;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.execution.MetricsMapper;
import org.districompute.app.faultlab.FaultLabViews.LabRunView;
import org.districompute.app.faultlab.FaultLabViews.LabStatus;
import org.districompute.app.faultlab.FaultLabViews.ScenarioView;
import org.districompute.app.faultlab.FaultLabViews.StartLabRun;
import org.districompute.app.monitoring.EventView;
import org.districompute.app.monitoring.RuntimeEventBridge;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.ExecutionEvent;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.districompute.app.persistence.FaultLabRun;
import org.districompute.app.persistence.FaultLabRunRepository;
import org.districompute.app.persistence.RunMetric;
import org.districompute.app.security.AuditService;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.task.ExecutionSettings;
import org.districompute.app.task.TaskDraft;
import org.districompute.app.task.TaskFactory;
import org.districompute.app.task.TaskFactory.PreparedTask;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.coordinator.TaskHandle;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.monitoring.RuntimeEvent;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.simulation.FaultProfile;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.PartitionSnapshot;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * Runs fault scenarios on an isolated coordinator ({@code docs/fault-lab.md}). Each run starts its own
 * DistriCompute-Distributed coordinator on a loopback port with a fresh secret (the only coordinator that accepts
 * fault-simulating workers), three in-process workers of which one has the scenario's fault profile, runs a
 * distributed matrix multiplication and a fault-free sequential reference, and records what the runtime detected
 * and how it recovered. Nothing of a lab run touches the application's coordinator, its workers or experiments; its
 * events are stored as simulated. One run at a time.
 */
@Service
public class FaultLabService {

    private static final Logger LOG = LoggerFactory.getLogger(FaultLabService.class);
    static final String ALGORITHM = "matrix.multiply";
    static final int SIZE = 192;
    static final int PARTITIONS = 12;
    static final int HEALTHY_WORKERS = 2;
    static final Duration RUN_LIMIT = Duration.ofSeconds(120);
    private static final Set<EventType> DETECTION = Set.of(EventType.ATTEMPT_FAILED, EventType.RESULT_IGNORED,
            EventType.WORKER_DISCONNECTED, EventType.WORKER_STATUS_CHANGED);
    private static final List<String> METRICS = List.of("distributed_time", "load_imbalance", "worker_utilization",
            "transfer_time_sum", "communication_ratio", "attempts", "failed_attempts", "retries", "workers_used");

    private final AppProperties properties;
    private final FaultLabRunRepository runs;
    private final ExecutionEventRepository events;
    private final TaskFactory tasks;
    private final AlgorithmRegistry registry;
    private final CurrentUser currentUser;
    private final AuditService audit;
    private final JsonSupport json;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<String> running = new AtomicReference<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
            Thread.ofPlatform().name("fault-lab").daemon(true).unstarted(r));

    FaultLabService(AppProperties properties, FaultLabRunRepository runs, ExecutionEventRepository events,
            TaskFactory tasks, AlgorithmRegistry registry, CurrentUser currentUser, AuditService audit,
            JsonSupport json, Clock clock) {
        this.properties = properties;
        this.runs = runs;
        this.events = events;
        this.tasks = tasks;
        this.registry = registry;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    /** Lab runs that were running when the application stopped did not finish. */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        for (FaultLabRun run : runs.findByStatus(FaultLabRun.Status.RUNNING)) {
            run.finish(FaultLabRun.Status.FAILED, null, "The application restarted before this lab run finished",
                    clock.instant());
            runs.save(run);
        }
    }

    @PreDestroy
    void stop() {
        executor.shutdownNow();
    }

    /** @return whether the lab is enabled, busy, and its scenarios */
    public LabStatus status() {
        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("algorithm", ALGORITHM);
        setup.put("size", SIZE + " x " + SIZE + " x " + SIZE);
        setup.put("partitions", PARTITIONS);
        setup.put("workers", "1 faulty and " + HEALTHY_WORKERS + " healthy, 2 slots each, in the application process");
        setup.put("schedulingPolicy", "ROUND_ROBIN");
        setup.put("heartbeat", "every 200 ms; suspect after 800 ms; lost after 2 s");
        setup.put("coordinator", "a separate coordinator on a loopback port, started for each run and stopped after");
        return new LabStatus(properties.faults().enabled(), running.get() != null, running.get(),
                Arrays.stream(FaultScenario.values()).map(s -> new ScenarioView(s.name(), s.title(), s.fault(),
                        s.expectedDetection(), s.expectedRecovery(), s.attemptTimeout().toSeconds(),
                        s.maxAttempts())).toList(), setup);
    }

    /**
     * Starts a scenario in the background.
     *
     * @param request scenario and seed
     * @return the new lab run
     */
    public LabRunView start(StartLabRun request) {
        if (!properties.faults().enabled()) {
            throw ApiException.conflict("FAULT_LAB_DISABLED", "The fault lab is disabled. An administrator enables "
                    + "it with districompute.faults.enabled=true.");
        }
        FaultScenario scenario;
        try {
            scenario = FaultScenario.valueOf(request.scenario());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("UNKNOWN_SCENARIO", "Unknown scenario " + request.scenario());
        }
        String id = UUID.randomUUID().toString();
        if (!running.compareAndSet(null, id)) {
            throw ApiException.conflict("FAULT_LAB_BUSY", "A lab run is in progress; wait until it finishes.");
        }
        try {
            long seed = request.seed() != null ? request.seed() : random.nextLong(1, Integer.MAX_VALUE);
            AppUser owner = currentUser.require();
            FaultProfile profile = scenario.profile(seed);
            FaultLabRun run = runs.save(new FaultLabRun(id, owner, scenario.name(), seed, ALGORITHM,
                    json.write(parameters(seed)), json.write(profileMap(profile)), clock.instant()));
            audit.record(owner.getUsername(), "FAULT_LAB_STARTED", "FAULT_LAB_RUN", id, scenario.name() + " seed="
                    + seed, null);
            executor.execute(() -> execute(id, scenario, seed, owner));
            return view(run);
        } catch (RuntimeException e) {
            running.set(null);
            throw e;
        }
    }

    /**
     * Recent lab runs.
     *
     * @param limit how many
     * @return runs, newest first
     */
    public List<LabRunView> list(int limit) {
        return runs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.min(100, Math.max(1, limit)))).stream()
                .map(this::view).toList();
    }

    /**
     * One lab run.
     *
     * @param id lab run
     * @return the run
     */
    public LabRunView get(String id) {
        return view(runs.findById(id).orElseThrow(() -> ApiException.notFound("Lab run", id)));
    }

    /**
     * The events of a lab run (all simulated).
     *
     * @param id lab run
     * @return events, oldest first
     */
    public List<EventView> events(String id) {
        runs.findById(id).orElseThrow(() -> ApiException.notFound("Lab run", id));
        return events.findByLabRunIdOrderByIdAsc(id, PageRequest.of(0, 2000)).stream()
                .map(e -> EventView.of(e, json.map(e.getDetails()))).toList();
    }

    private void execute(String id, FaultScenario scenario, long seed, AppUser owner) {
        FaultLabRun.Status status = FaultLabRun.Status.COMPLETED;
        Map<String, Object> outcome = null;
        String error = null;
        try {
            outcome = run(id, scenario, seed, owner);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = FaultLabRun.Status.FAILED;
            error = "The lab run was interrupted";
        } catch (Exception e) {
            LOG.warn("Fault lab run {} ({}) could not be completed", id, scenario, e);
            status = FaultLabRun.Status.FAILED;
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try {
                FaultLabRun run = runs.findById(id).orElseThrow();
                run.finish(status, outcome == null ? null : json.write(outcome), error, clock.instant());
                runs.save(run);
                audit.record(owner.getUsername(), "FAULT_LAB_FINISHED", "FAULT_LAB_RUN", id,
                        status + (outcome == null ? "" : " " + outcome.get("verdict")), null);
            } finally {
                running.set(null);
            }
        }
    }

    private Map<String, Object> run(String id, FaultScenario scenario, long seed, AppUser owner) throws Exception {
        String prefix = "lab-" + id.substring(0, 8);
        byte[] key = new byte[32];
        random.nextBytes(key);
        SharedSecret secret = SharedSecret.of(Base64.getUrlEncoder().withoutPadding().encodeToString(key));
        CoordinatorConfig config = CoordinatorConfig.builder(secret).coordinatorId("fault-" + prefix)
                .bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(800), Duration.ofSeconds(2))
                .allowFaultSimulationWorkers(true).registry(registry).build();
        FaultProfile profile = scenario.profile(seed);
        ConcurrentLinkedQueue<RuntimeEvent> pending = new ConcurrentLinkedQueue<>();
        List<RuntimeEvent> seen = new ArrayList<>();
        List<WorkerNode> nodes = new ArrayList<>();
        String faulty = prefix + "-faulty";
        try (Coordinator lab = Coordinator.start(config);
                AutoCloseable subscription = lab.events().subscribe(e -> {
                    if (!RuntimeEventBridge.TRANSIENT.contains(e.type())) {
                        pending.add(e);
                    }
                }, 0)) {
            lab.setSchedulingPolicy(PolicyName.ROUND_ROBIN);
            List<String> healthy = new ArrayList<>();
            for (int i = 1; i <= HEALTHY_WORKERS; i++) {
                healthy.add(prefix + "-healthy-" + i);
            }
            nodes.add(worker(lab, secret, faulty, profile));
            for (String w : healthy) {
                nodes.add(worker(lab, secret, w, FaultProfile.NONE));
            }
            for (WorkerNode node : nodes) {
                if (!node.awaitRegistered(Duration.ofSeconds(15))) {
                    throw new IllegalStateException("A lab worker did not register: " + node.lastError());
                }
            }

            PreparedTask distributed = tasks.prepare(draft(seed, new ExecutionSettings("DISTRIBUTED_CPU", null,
                    PARTITIONS, null, null, null, null, "ROUND_ROBIN", null, scenario.maxAttempts(),
                    (int) scenario.attemptTimeout().toSeconds(), (int) RUN_LIMIT.toSeconds(), null, null, null)),
                    owner, true);
            SubmitOptions options = SubmitOptions.DEFAULTS.withPolicy(PolicyName.ROUND_ROBIN)
                    .withMaxAttempts(scenario.maxAttempts()).withAttemptTimeout(scenario.attemptTimeout());
            Instant submitted = clock.instant();
            TaskHandle handle = lab.submit(tasks.spec(distributed, prefix + "-run", "fault-" + id), options);
            TaskOutcome outcome = await(handle, id, pending, seen);

            PreparedTask sequential = tasks.prepare(draft(seed, ExecutionSettings.of("SEQUENTIAL_CPU")), owner, true);
            TaskOutcome reference = await(lab.submit(tasks.spec(sequential, prefix + "-reference", "fault-" + id)), id,
                    pending, seen);
            Thread.sleep(300);
            flush(id, pending, seen);
            return analyse(scenario, profile, faulty, outcome, reference, submitted, seen, nodes.size());
        } finally {
            nodes.forEach(WorkerNode::abort);
        }
    }

    private WorkerNode worker(Coordinator lab, SharedSecret secret, String id, FaultProfile profile) {
        return WorkerNode.start(WorkerConfig.builder("127.0.0.1", lab.port(), secret).workerId(id)
                .maxConcurrentTasks(2).backoff(Duration.ofMillis(200), Duration.ofSeconds(1)).faults(profile)
                .label("lab", "true").build());
    }

    private TaskOutcome await(TaskHandle handle, String labRunId, ConcurrentLinkedQueue<RuntimeEvent> pending,
            List<RuntimeEvent> seen) throws Exception {
        long deadline = System.nanoTime() + RUN_LIMIT.toNanos();
        while (!handle.completion().isDone()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The lab task did not finish within " + RUN_LIMIT.toSeconds() + " s");
            }
            Thread.sleep(200);
            flush(labRunId, pending, seen);
        }
        return handle.completion().get(1, TimeUnit.SECONDS);
    }

    /** Stores the events received so far, marked as simulated and linked to the lab run. */
    private void flush(String labRunId, ConcurrentLinkedQueue<RuntimeEvent> pending, List<RuntimeEvent> seen) {
        List<ExecutionEvent> rows = new ArrayList<>();
        RuntimeEvent e;
        while ((e = pending.poll()) != null) {
            seen.add(e);
            ExecutionEvent row = new ExecutionEvent(null, null, e.attemptId() != null ? e.attemptId() : e.partitionId(),
                    e.workerId(), e.sequence(), e.type().name(), RuntimeEventBridge.severity(e), truncate(e.message()),
                    json.write(e.details()), true, e.timestamp());
            row.setLabRunId(labRunId);
            rows.add(row);
        }
        if (!rows.isEmpty()) {
            events.saveAll(rows);
        }
    }

    private Map<String, Object> analyse(FaultScenario scenario, FaultProfile profile, String faulty,
            TaskOutcome outcome, TaskOutcome reference, Instant submitted, List<RuntimeEvent> seen, int workers) {
        Map<String, Object> out = new LinkedHashMap<>();
        String status = outcome.result().status().name();
        String digest = outcome.result().result() == null ? null : outcome.result().result().digest();
        String referenceDigest = reference.result().result() == null ? null : reference.result().result().digest();
        boolean matches = digest != null && digest.equals(referenceDigest);

        RuntimeEvent detection = seen.stream()
                .filter(e -> DETECTION.contains(e.type()) && faulty.equals(e.workerId()))
                .filter(e -> !e.timestamp().isBefore(submitted)).findFirst().orElse(null);
        RuntimeEvent firstRetry = seen.stream().filter(e -> e.type() == EventType.PARTITION_RETRIED).findFirst()
                .orElse(null);

        Map<String, Integer> byStatus = new TreeMap<>();
        Map<String, Map<String, Object>> byWorker = new TreeMap<>();
        int recovered = 0;
        for (PartitionSnapshot p : outcome.snapshot().partitions()) {
            boolean troubled = false;
            for (AttemptSnapshot a : p.attempts()) {
                byStatus.merge(a.status().name(), 1, Integer::sum);
                String w = a.workerId() == null ? "unassigned" : a.workerId();
                Map<String, Object> entry = byWorker.computeIfAbsent(w, k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("role", k.equals(faulty) ? "faulty" : "healthy");
                    m.put("attempts", new TreeMap<String, Integer>());
                    m.put("computeMs", 0.0);
                    return m;
                });
                @SuppressWarnings("unchecked")
                Map<String, Integer> statuses = (Map<String, Integer>) entry.get("attempts");
                statuses.merge(a.status().name(), 1, Integer::sum);
                entry.put("computeMs", (Double) entry.get("computeMs") + Math.max(0, a.computeNanos()) / 1e6);
                troubled |= !"SUCCEEDED".equals(a.status().name());
            }
            if (troubled && p.status() == PartitionStatus.COMPLETED) {
                recovered++;
            }
        }

        String verdict;
        String verdictText;
        if (!"COMPLETED".equals(status)) {
            verdict = "NOT_RECOVERED";
            verdictText = "The task did not complete: " + (outcome.result().error() == null ? status
                    : outcome.result().error().message());
        } else if (!matches) {
            verdict = "DIFFERENT_RESULT";
            verdictText = "The task completed, but its result differs from the fault-free reference.";
        } else if (detection != null) {
            verdict = "RECOVERED";
            verdictText = "The fault was detected and the task completed with the same result as the fault-free "
                    + "reference.";
        } else {
            verdict = "UNAFFECTED";
            verdictText = "No failure was detected and the task completed with the same result as the fault-free "
                    + "reference.";
        }
        out.put("verdict", verdict);
        out.put("verdictText", verdictText);
        out.put("scenario", scenario.name());
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("status", status);
        task.put("error", outcome.result().error() == null ? null : outcome.result().error().message());
        task.put("digest", digest);
        task.put("referenceDigest", referenceDigest);
        task.put("resultMatchesReference", matches);
        task.put("verified", outcome.result().verification() == null ? null
                : outcome.result().verification().passed());
        task.put("durationMs", millis(outcome.snapshot().submittedAt(), outcome.snapshot().finishedAt()));
        task.put("referenceDurationMs", millis(reference.snapshot().submittedAt(),
                reference.snapshot().finishedAt()));
        task.put("partitions", outcome.snapshot().totalPartitions());
        task.put("attempts", outcome.snapshot().attempts());
        task.put("retries", outcome.snapshot().retries());
        out.put("task", task);
        out.put("detection", detection == null ? null : eventSummary(detection, submitted));
        Map<String, Object> recovery = new LinkedHashMap<>();
        recovery.put("retries", outcome.snapshot().retries());
        recovery.put("recoveredPartitions", recovered);
        recovery.put("firstRetry", firstRetry == null ? null : eventSummary(firstRetry, submitted));
        recovery.put("finishedAfterMs", millis(submitted, outcome.snapshot().finishedAt()));
        out.put("recovery", recovery);
        out.put("attemptsByStatus", byStatus);
        out.put("workers", byWorker);
        out.put("faultyWorker", faulty);
        out.put("faults", profile.describe());
        out.put("workerCount", workers);
        Map<String, Object> metrics = new LinkedHashMap<>();
        if (outcome.snapshot().metrics() != null) {
            for (RunMetric m : MetricsMapper.map(null, null, 0, outcome.result(), outcome.snapshot().metrics(),
                    clock.instant())) {
                if (METRICS.contains(m.getMetricName())) {
                    metrics.put(m.getMetricName(), m.getMetricValue());
                }
            }
        }
        out.put("metrics", metrics);
        out.put("events", seen.size());
        return out;
    }

    private static Map<String, Object> eventSummary(RuntimeEvent e, Instant since) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", e.type().name());
        m.put("workerId", e.workerId());
        m.put("message", e.message());
        m.put("afterMs", millis(since, e.timestamp()));
        return m;
    }

    private static Long millis(Instant from, Instant to) {
        return from == null || to == null ? null : Duration.between(from, to).toMillis();
    }

    private TaskDraft draft(long seed, ExecutionSettings settings) {
        return new TaskDraft(ALGORITHM, parameters(seed), Map.of(), settings);
    }

    private static Map<String, Object> parameters(long seed) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("rowsA", SIZE);
        p.put("colsA", SIZE);
        p.put("colsB", SIZE);
        p.put("seed", seed);
        return p;
    }

    private static Map<String, Object> profileMap(FaultProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("description", p.describe());
        m.put("responseDelayMillis", p.responseDelayMillis());
        m.put("messageLatencyMillis", p.messageLatencyMillis());
        m.put("slowdownFactor", p.slowdownFactor());
        m.put("disconnectAfterTasks", p.disconnectAfterTasks());
        m.put("stopHeartbeatsAfterMillis", p.stopHeartbeatsAfterMillis());
        m.put("hangProbability", p.hangProbability());
        m.put("failProbability", p.failProbability());
        m.put("corruptProbability", p.corruptProbability());
        m.put("duplicateProbability", p.duplicateProbability());
        return m;
    }

    private LabRunView view(FaultLabRun r) {
        FaultScenario scenario = FaultScenario.valueOf(r.getScenario());
        return new LabRunView(r.getId(), r.getScenario(), scenario.title(), r.getOwner().getUsername(),
                r.getStatus().name(), true, r.getSeed(), r.getAlgorithmId(), json.map(r.getParameters()),
                json.map(r.getFaultProfile()), r.getOutcome() == null ? null : json.map(r.getOutcome()),
                r.getErrorMessage(), r.getCreatedAt(), r.getCompletedAt());
    }

    private static String truncate(String message) {
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }
}
