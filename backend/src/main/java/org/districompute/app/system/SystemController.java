package org.districompute.app.system;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.experiment.ExperimentService;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.monitoring.EventRecorder;
import org.districompute.app.monitoring.EventView;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.security.CurrentUser;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.metrics.CoordinatorMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Versions, runtime status and the overview dashboard ({@code /api/v1/system}, {@code /api/v1/dashboard}). */
@RestController
@RequestMapping("/api/v1")
public class SystemController {

    private final Coordinator coordinator;
    private final AlgorithmRegistry registry;
    private final AppProperties properties;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final ExperimentService experimentService;
    private final ExecutionEventRepository events;
    private final EventRecorder recorder;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final Clock clock;
    private final String version;

    SystemController(Coordinator coordinator, AlgorithmRegistry registry, AppProperties properties,
            ExperimentRepository experiments, ExperimentRunRepository runs, ExperimentService experimentService,
            ExecutionEventRepository events, EventRecorder recorder, CurrentUser currentUser, JdbcTemplate jdbc,
            JsonSupport json, Clock clock, @Value("${districompute.version:0.1.0-SNAPSHOT}") String version) {
        this.coordinator = coordinator;
        this.registry = registry;
        this.properties = properties;
        this.experiments = experiments;
        this.runs = runs;
        this.experimentService = experimentService;
        this.events = events;
        this.recorder = recorder;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
        this.version = version;
    }

    /**
     * Versions of the application and its components (public).
     *
     * @return the versions
     */
    @GetMapping("/system/info")
    public ComponentVersions info() {
        return ComponentVersions.current(version);
    }

    /**
     * Runtime status: coordinator, workers, resources and health checks.
     *
     * @return the status
     */
    @GetMapping("/system/status")
    public Map<String, Object> status() {
        CoordinatorMetrics m = coordinator.metrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", ComponentVersions.current(version));
        out.put("coordinator", Map.of("id", m.coordinatorId(), "workerPort", coordinator.port(), "advertisedHost",
                properties.coordinator().advertisedHost(), "tls", properties.coordinator().tlsKeystore() != null,
                "startedAt", m.startedAt(), "uptimeSeconds", m.uptimeMillis() / 1000,
                "schedulingPolicy", coordinator.schedulingPolicy().name()));
        out.put("workers", Map.of("byStatus", m.workersByStatus(), "totalSlots", m.totalSlots(), "busySlots",
                m.busySlots()));
        out.put("runtime", json.toMap(m));
        out.put("local", Map.of("cores", Runtime.getRuntime().availableProcessors(), "maxHeapBytes",
                Runtime.getRuntime().maxMemory(), "gpuAvailable", !registry.gpuStatus().available().isEmpty(),
                "gpuStatus", registry.gpuStatus().summary()));
        out.put("health", health());
        return out;
    }

    /**
     * The overview dashboard. Every number comes from the database or the running coordinator.
     *
     * @return the dashboard
     */
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public Map<String, Object> dashboard() {
        AppUser user = currentUser.require();
        boolean admin = currentUser.isAdmin();
        Long owner = admin ? null : user.getId();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED")) {
            counts.put(status, 0L);
        }
        for (Object[] row : experiments.countByStatus(owner)) {
            counts.put(row[0].toString(), (Long) row[1]);
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        List<Experiment> recent = experiments.recent(owner, clock.instant().minus(Duration.ofDays(3650)),
                PageRequest.of(0, 8)).getContent();
        Map<String, List<ExperimentRun>> recentRuns = new LinkedHashMap<>();
        runs.findByExperimentIdIn(recent.stream().map(Experiment::getId).toList())
                .forEach(r -> recentRuns.computeIfAbsent(r.getExperimentId(), k -> new ArrayList<>()).add(r));
        List<ExperimentSummary> recentViews = recent.stream().map(e -> experimentService.summary(e,
                recentRuns.getOrDefault(e.getId(), List.of()))).toList();

        CoordinatorMetrics m = coordinator.metrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", admin ? "all users" : "your experiments");
        out.put("experiments", Map.of("total", total, "byStatus", counts));
        out.put("workers", Map.of("byStatus", m.workersByStatus(), "connected", m.workersByStatus().entrySet()
                .stream().filter(e -> !"DISCONNECTED".equals(e.getKey())).mapToInt(Map.Entry::getValue).sum(),
                "totalSlots", m.totalSlots(), "busySlots", m.busySlots()));
        out.put("resources", Map.of("localCores", Runtime.getRuntime().availableProcessors(), "workerSlots",
                m.totalSlots(), "gpuAvailable", !registry.gpuStatus().available().isEmpty()));
        out.put("recentExperiments", recentViews);
        out.put("recentEvents", events.findBySimulatedFalseOrderByIdDesc(PageRequest.of(0, 40)).stream()
                .filter(e -> admin || e.getExperimentId() == null || recent.stream()
                        .anyMatch(x -> x.getId().equals(e.getExperimentId())))
                .limit(12).map(e -> EventView.of(e, Map.of())).toList());
        out.put("performance", performance(owner));
        out.put("health", health());
        return out;
    }

    private List<Map<String, Object>> performance(Long owner) {
        List<Experiment> latest = experiments.findAll(PageRequest.of(0, 200, Sort.by("createdAt").descending()))
                .getContent().stream().filter(e -> owner == null || e.getOwner().getId().equals(owner)).toList();
        Map<String, List<Double>> byMode = new LinkedHashMap<>();
        for (ExperimentRun r : runs.findByExperimentIdIn(latest.stream().map(Experiment::getId).toList())) {
            if (r.getStatus() == RunStatus.COMPLETED && r.getWallTimeMs() != null) {
                byMode.computeIfAbsent(r.getExecutionMode(), k -> new ArrayList<>()).add(r.getWallTimeMs());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        byMode.forEach((mode, values) -> {
            double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            out.add(Map.of("mode", mode, "completedRuns", sorted.length, "medianWallTimeMs",
                    sorted.length % 2 == 1 ? sorted[sorted.length / 2]
                            : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2));
        });
        return out;
    }

    private Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        boolean database;
        try {
            database = Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
        } catch (RuntimeException e) {
            database = false;
        }
        health.put("database", database ? "UP" : "DOWN");
        health.put("coordinator", coordinator.port() > 0 ? "UP" : "DOWN");
        health.put("eventsDropped", recorder.dropped());
        return health;
    }
}
