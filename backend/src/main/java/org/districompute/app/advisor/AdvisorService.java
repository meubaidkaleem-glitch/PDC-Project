package org.districompute.app.advisor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.districompute.app.advisor.AdviceView.Measured;
import org.districompute.app.advisor.AdviceView.ModeAdvice;
import org.districompute.app.advisor.AdviceView.ResourcesView;
import org.districompute.app.advisor.AdviceView.WorkloadView;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.Enums.AdviceBasis;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.persistence.RunMetric;
import org.districompute.app.persistence.RunMetricRepository;
import org.districompute.app.task.TaskFactory;
import org.districompute.app.task.TaskFactory.PreparedTask;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.scheduling.Calibration;
import org.districompute.core.scheduling.StrategyAssessment;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.strategy.ExecutionAdvice;
import org.districompute.distributed.strategy.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The execution strategy advisor. It does not estimate anything itself: Core's {@code StrategyAdvisor} assesses
 * every mode with the resources the Distributed coordinator reports ({@code Coordinator.advise}). The advisor adds
 * this application's measurements of identical workloads, the basis and confidence of each estimate, the
 * application's limits, and warnings ({@code docs/advisor.md}).
 */
@Service
public class AdvisorService {

    private static final Logger LOG = LoggerFactory.getLogger(AdvisorService.class);
    private static final int HISTORY_EXPERIMENTS = 200;

    private final Coordinator coordinator;
    private final TaskFactory tasks;
    private final AlgorithmRegistry registry;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final RunMetricRepository metrics;
    private final JsonSupport json;

    AdvisorService(Coordinator coordinator, TaskFactory tasks, AlgorithmRegistry registry,
            ExperimentRepository experiments, ExperimentRunRepository runs, RunMetricRepository metrics,
            JsonSupport json) {
        this.coordinator = coordinator;
        this.tasks = tasks;
        this.registry = registry;
        this.experiments = experiments;
        this.runs = runs;
        this.metrics = metrics;
        this.json = json;
    }

    /**
     * Advice for a validated task.
     *
     * @param task the task
     * @return the advice
     */
    @Transactional(readOnly = true)
    public AdviceView advise(PreparedTask task) {
        ExecutionAdvice advice = coordinator.advise(tasks.spec(task, "advice-probe", null));
        Map<String, Measured> measured = measured(task.info().descriptor().id().value(), task.profile().dimensions());
        Set<ExecutionMode> executable = Set.copyOf(advice.runtime().executableModes());

        List<ModeAdvice> modes = new ArrayList<>();
        for (StrategyAssessment a : advice.assessment().assessments()) {
            Measured m = measured.get(a.mode().name());
            String basis = m != null ? "MEASURED" : a.estimatedSeconds() == null ? "NONE"
                    : advice.assessment().calibrated() ? "HISTORICAL" : "THEORETICAL";
            boolean runnable = executable.contains(a.mode())
                    && task.info().capabilities().supportedModes().contains(a.mode());
            modes.add(new ModeAdvice(a.mode().name(), a.verdict().name(), runnable, a.threads(), a.estimatedSeconds(),
                    basis, m, a.reasons()));
        }

        ExecutionMode recommended = advice.recommended();
        ModeAdvice best = recommended == null ? null
                : modes.stream().filter(m -> m.mode().equals(recommended.name())).findFirst().orElse(null);
        String confidence;
        AdviceBasis basis;
        if (best == null) {
            confidence = "NONE";
            basis = AdviceBasis.NONE;
        } else if (best.measured() != null && best.measured().runs() >= 3) {
            confidence = "HIGH";
            basis = AdviceBasis.HISTORICAL;
        } else if (best.measured() != null || advice.assessment().calibrated()) {
            confidence = "MEDIUM";
            basis = AdviceBasis.HISTORICAL;
        } else if (best.estimatedSeconds() != null) {
            confidence = "LOW";
            basis = AdviceBasis.THEORETICAL;
        } else {
            confidence = "NONE";
            basis = AdviceBasis.NONE;
        }
        boolean reliable = !"NONE".equals(confidence);
        String explanation = reliable ? advice.explanation()
                : recommended == null
                        ? "No execution mode is suitable for this task with the current resources."
                        : "Neither an estimate nor a measurement exists for " + describe(recommended)
                                + " yet, so the modes cannot be compared by time. It is preferred only for the "
                                + "structural reasons listed per mode below. Completed runs of this workload "
                                + "calibrate later recommendations.";

        RuntimeContext runtime = advice.runtime();
        Double communication = runtime.transferBytesPerSecond() == null || runtime.transferBytesPerSecond() <= 0 ? null
                : runtime.estimatedTransferBytes() / runtime.transferBytesPerSecond();
        ResourcesView resources = new ResourcesView(Runtime.getRuntime().availableProcessors(),
                runtime.eligibleWorkers(), runtime.ineligibleWorkers().stream()
                        .map(r -> Map.of("workerId", r.workerId(), "reason", r.reason())).toList(),
                runtime.totalSlots(), runtime.busySlots(), runtime.pendingPartitions(),
                !registry.gpuStatus().available().isEmpty(), registry.gpuStatus().summary(),
                runtime.transferBytesPerSecond() != null, runtime.transferBytesPerSecond(), runtime.latencyMillis(),
                runtime.estimatedTransferBytes(), communication);

        Set<String> warnings = new LinkedHashSet<>(runtime.notes());
        if (runtime.eligibleWorkers().isEmpty()) {
            warnings.add("No connected worker can run " + task.info().descriptor().id() + " now. A distributed run "
                    + "would wait for one (30 s by default) and then fail.");
        }
        if (runtime.transferBytesPerSecond() == null
                && warnings.stream().noneMatch(w -> w.contains("network has not been measured"))) {
            warnings.add("The network has not been measured yet, so the communication cost of a distributed run is "
                    + "unknown until one completes.");
        }
        modes.stream().filter(m -> "SEQUENTIAL_CPU".equals(m.mode()) && m.estimatedSeconds() != null
                && m.estimatedSeconds() < 0.05).findFirst().ifPresent(m -> warnings.add("The workload is small "
                        + "(estimated " + String.format("%.3f", m.estimatedSeconds()) + " s sequentially): fixed "
                        + "costs of partitioning and communication can outweigh any parallel speedup."));
        if (!measured.isEmpty()) {
            warnings.add("Measured times come from earlier runs of the same algorithm with the same dimensions; their "
                    + "threads, workers or machine load may differ from now.");
        }
        return new AdviceView(advice.createdAt(), task.info().descriptor().id().value(), workload(task.profile()),
                reliable ? recommended.name() : null, reliable, confidence, basis.name(), explanation, modes, resources,
                advice.assessment().assumptions(), List.copyOf(warnings));
    }

    /** Human-readable name of a mode, as the user interface shows it. */
    static String describe(ExecutionMode mode) {
        return switch (mode) {
            case SEQUENTIAL_CPU -> "sequential CPU execution";
            case MULTITHREADED_CPU -> "parallel CPU execution (threads)";
            case DISTRIBUTED_CPU -> "distributed CPU execution (workers)";
            default -> mode.name();
        };
    }

    /**
     * Gives the coordinator the throughput measured in earlier local runs, so that Core's estimates are calibrated
     * after a restart (the coordinator keeps its own measurements in memory only).
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void seedCalibrations() {
        List<ExperimentRun> local = runs.findAll(PageRequest.of(0, 1000)).getContent().stream()
                .filter(r -> r.getStatus() == RunStatus.COMPLETED && !"DISTRIBUTED_CPU".equals(r.getExecutionMode()))
                .toList();
        if (local.isEmpty()) {
            return;
        }
        Map<Long, ExperimentRun> byId = new LinkedHashMap<>();
        local.forEach(r -> byId.put(r.getId(), r));
        Map<Long, Double> operations = new LinkedHashMap<>();
        Map<Long, Double> executionMs = new LinkedHashMap<>();
        for (RunMetric m : metrics.findByRunIdInAndMetricName(byId.keySet(), "estimated_operations")) {
            operations.put(m.getRunId(), m.getMetricValue());
        }
        for (RunMetric m : metrics.findByRunIdInAndMetricName(byId.keySet(), "execution_time")) {
            executionMs.put(m.getRunId(), m.getMetricValue());
        }
        List<Calibration> calibrations = new ArrayList<>();
        for (ExperimentRun run : local) {
            Double ops = operations.get(run.getId());
            Double ms = executionMs.get(run.getId());
            if (ops == null || ms == null || ops <= 0 || ms <= 0) {
                continue;
            }
            String algorithm = experiments.findById(run.getExperimentId()).map(Experiment::getAlgorithmId).orElse(null);
            if (algorithm == null) {
                continue;
            }
            int threads = run.getThreads() == null ? 1 : Math.max(1, run.getThreads());
            calibrations.add(new Calibration(AlgorithmId.of(algorithm), ExecutionMode.valueOf(run.getExecutionMode()),
                    threads, ops / (ms / 1000.0), ops));
        }
        if (!calibrations.isEmpty()) {
            coordinator.addCalibrations(calibrations);
            LOG.info("Seeded the coordinator with {} calibrations from earlier runs", calibrations.size());
        }
    }

    private Map<String, Measured> measured(String algorithm, Map<String, Long> dimensions) {
        List<Experiment> candidates = experiments.findAll(PageRequest.of(0, HISTORY_EXPERIMENTS,
                org.springframework.data.domain.Sort.by("createdAt").descending())).getContent().stream()
                .filter(e -> e.getAlgorithmId().equals(algorithm) && e.getStatus() == ExperimentStatus.COMPLETED)
                .filter(e -> Objects.equals(dimensionsOf(e), dimensions)).toList();
        if (candidates.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Double>> seconds = new LinkedHashMap<>();
        for (ExperimentRun run : runs.findByExperimentIdInAndStatus(candidates.stream().map(Experiment::getId)
                .toList(), RunStatus.COMPLETED)) {
            if (run.getWallTimeMs() != null) {
                seconds.computeIfAbsent(run.getExecutionMode(), k -> new ArrayList<>()).add(run.getWallTimeMs() / 1000);
            }
        }
        Map<String, Measured> out = new LinkedHashMap<>();
        seconds.forEach((mode, values) -> {
            double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            out.put(mode, new Measured(sorted.length, median(sorted), sorted[0], sorted[sorted.length - 1]));
        });
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> dimensionsOf(Experiment e) {
        Object dims = json.map(e.getInputSummary()).get("dimensions");
        if (!(dims instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Long> out = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((k, v) -> out.put(k, v instanceof Number n ? n.longValue() : null));
        return out;
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    /**
     * Maps Core's workload analysis.
     *
     * @param p Core's profile
     * @return the view
     */
    public static WorkloadView workload(WorkloadProfile p) {
        return new WorkloadView(p.dimensions(), p.estimatedOperations(), p.operationUnit(), p.timeComplexity(),
                p.inputBytes(), p.outputBytes(), p.estimatedPeakMemoryBytes(), p.parallelism().partitionable(),
                p.parallelism().strategy() == null ? null : p.parallelism().strategy().name(),
                p.parallelism().maxUsefulPartitions(),
                p.parallelism().dataDependency() == null ? null : p.parallelism().dataDependency().name(),
                p.parallelism().aggregationComplexity());
    }
}
