package org.districompute.app.analysis;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.districompute.app.analysis.AnalysisViews.CompareRequest;
import org.districompute.app.analysis.AnalysisViews.Compared;
import org.districompute.app.analysis.AnalysisViews.Comparison;
import org.districompute.app.analysis.AnalysisViews.Issue;
import org.districompute.app.analysis.AnalysisViews.RunPoint;
import org.districompute.app.analysis.AnalysisViews.Stats;
import org.districompute.app.analysis.AnalysisViews.WorkloadGroup;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.experiment.ExperimentAccess;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.persistence.RunMetric;
import org.districompute.app.persistence.RunMetricRepository;
import org.districompute.app.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares completed experiments and groups them by workload ({@code docs/analysis.md}). Every figure is computed
 * from stored measurements of completed runs; nothing is estimated here.
 */
@Service
public class AnalysisService {

    /** Most experiments in one comparison. */
    public static final int MAX_COMPARED = 20;
    /** Coefficient of variation above which a comparison warns about noisy measurements. */
    static final double HIGH_VARIATION = 0.10;
    /** Baseline median below which fixed overheads dominate. */
    static final double SMALL_WORKLOAD_MS = 50;
    /** Basis of speedups: Core's execution time. */
    public static final String EXECUTION_TIME = "EXECUTION_TIME";
    /** Basis of speedups: wall time measured by the application. */
    public static final String WALL_TIME = "WALL_TIME";
    /** Share of the wall time spent waiting to start above which a wall-time comparison warns. */
    static final double SIGNIFICANT_WAIT = 0.10;
    /** Experiments scanned to build workload groups. */
    static final int WORKLOAD_SCAN = 1000;

    /** Core's phases, in execution order; their sum and the wall time give the breakdown. */
    static final List<String> PHASES = List.of("validation_time", "input_preparation_time", "partitioning_time",
            "compute_time", "aggregation_time", "verification_time");
    /** Further metrics summarised per experiment when present. */
    static final List<String> INDICATORS = List.of("throughput_operations", "communication_ratio",
            "worker_utilization", "load_imbalance", "cpu_utilization", "transfer_time_sum", "bytes_sent",
            "bytes_received", "attempts", "retries", "failed_attempts");

    private static final Set<ExperimentStatus> FINISHED = Set.of(ExperimentStatus.COMPLETED,
            ExperimentStatus.FAILED, ExperimentStatus.CANCELLED);

    private final ExperimentAccess access;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final RunMetricRepository metrics;
    private final CurrentUser currentUser;
    private final JsonSupport json;
    private final Clock clock;

    AnalysisService(ExperimentAccess access, ExperimentRepository experiments, ExperimentRunRepository runs,
            RunMetricRepository metrics, CurrentUser currentUser, JsonSupport json, Clock clock) {
        this.access = access;
        this.experiments = experiments;
        this.runs = runs;
        this.metrics = metrics;
        this.currentUser = currentUser;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Compares experiments the current user may see.
     *
     * @param request the experiments and an optional baseline
     * @return the comparison
     */
    @Transactional(readOnly = true)
    public Comparison compare(CompareRequest request) {
        List<String> ids = request.experimentIds();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw ApiException.badRequest("DUPLICATE_EXPERIMENT", "Each experiment can be compared once");
        }
        if (request.baselineId() != null && !ids.contains(request.baselineId())) {
            throw ApiException.badRequest("BASELINE_NOT_COMPARED", "The baseline must be one of the compared experiments");
        }
        List<Experiment> selected = ids.stream().map(access::require).toList();
        Map<String, List<ExperimentRun>> completed = runs.findByExperimentIdInAndStatus(ids, RunStatus.COMPLETED)
                .stream().sorted(Comparator.comparingInt(ExperimentRun::getRunIndex))
                .collect(Collectors.groupingBy(ExperimentRun::getExperimentId));
        Set<Long> completedRunIds = completed.values().stream().flatMap(List::stream).map(ExperimentRun::getId)
                .collect(Collectors.toSet());
        Map<Long, Map<String, Double>> metricsByRun = new TreeMap<>();
        for (RunMetric m : metrics.findByExperimentIdIn(ids)) {
            if (completedRunIds.contains(m.getRunId())) {
                metricsByRun.computeIfAbsent(m.getRunId(), k -> new LinkedHashMap<>())
                        .putIfAbsent(m.getMetricName(), m.getMetricValue());
            }
        }

        List<Measured> measured = selected.stream()
                .map(e -> measure(e, completed.getOrDefault(e.getId(), List.of()), metricsByRun)).toList();
        List<Issue> issues = new ArrayList<>();
        checkCompatibility(measured, issues);
        boolean executionKnown = measured.stream().filter(m -> m.wall != null).allMatch(m -> m.execution != null);
        String basis = request.basis() != null ? request.basis() : executionKnown ? EXECUTION_TIME : WALL_TIME;
        if (EXECUTION_TIME.equals(basis) && !executionKnown) {
            basis = WALL_TIME;
            issues.add(new Issue("WARNING", "BASIS_UNAVAILABLE", "Not every experiment reports Core's execution time, "
                    + "so the comparison uses wall time.", List.of()));
        }

        Measured baseline = chooseBaseline(measured, request.baselineId());
        String baselineReason;
        if (request.baselineId() != null) {
            baselineReason = "chosen by you";
        } else if (baseline != null && "SEQUENTIAL_CPU".equals(baseline.experiment.getExecutionMode())) {
            baselineReason = "sequential execution, the reference implementation";
        } else {
            baselineReason = "no sequential experiment with completed runs is among those compared, so the first one "
                    + "with completed runs is the baseline";
        }
        if (baseline == null || baseline.wall == null) {
            issues.add(new Issue("ERROR", "BASELINE_WITHOUT_RUNS", "The baseline has no completed run, so no speedup "
                    + "can be computed.", baseline == null ? List.of() : List.of(baseline.experiment.getId())));
        }
        boolean comparable = issues.stream().noneMatch(i -> "ERROR".equals(i.severity()));
        boolean sequentialBaseline = baseline != null
                && "SEQUENTIAL_CPU".equals(baseline.experiment.getExecutionMode());
        if (comparable && !sequentialBaseline) {
            issues.add(new Issue("INFO", "EFFICIENCY_NEEDS_SEQUENTIAL", "Parallel efficiency is only computed "
                    + "against a sequential baseline.", List.of()));
        }
        checkMeasurements(measured, baseline, comparable, basis, issues);
        checkConcurrency(measured, issues);
        checkWaiting(measured, basis, issues);

        String chosen = basis;
        List<Compared> compared = measured.stream()
                .map(m -> compared(m, comparable ? baseline : null, sequentialBaseline, chosen)).toList();
        String description = EXECUTION_TIME.equals(basis)
                ? "Core's execution time: validation, input preparation, partitioning, computation (on threads or "
                        + "workers), aggregation and verification; median of completed runs"
                : "wall time measured by the application from submission to the stored result, including waiting "
                        + "for other runs; median of completed runs";
        return new Comparison(Instant.now(clock), comparable, baseline == null ? null : baseline.experiment.getId(),
                baselineReason, basis, description, compared, issues, summary(compared, comparable, baseline, basis));
    }

    /**
     * Completed experiments of the current user (all users for administrators) grouped by workload.
     *
     * @return groups, newest first
     */
    @Transactional(readOnly = true)
    public List<WorkloadGroup> workloads() {
        Long ownerId = currentUser.isAdmin() ? null : currentUser.require().getId();
        List<Experiment> recent = experiments.findFinished(ownerId, FINISHED, PageRequest.of(0, WORKLOAD_SCAN));
        Set<String> withRuns = runs.findByExperimentIdInAndStatus(recent.stream().map(Experiment::getId).toList(),
                RunStatus.COMPLETED).stream().map(ExperimentRun::getExperimentId).collect(Collectors.toSet());
        Map<String, List<Experiment>> groups = new LinkedHashMap<>();
        for (Experiment e : recent) {
            if (withRuns.contains(e.getId())) {
                String key = e.getAlgorithmId() + "@" + e.getAlgorithmVersion() + " " + canonical(dimensions(e));
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            }
        }
        List<WorkloadGroup> out = new ArrayList<>();
        groups.forEach((key, list) -> {
            Map<String, Integer> modes = new TreeMap<>();
            list.forEach(e -> modes.merge(e.getExecutionMode(), 1, Integer::sum));
            Experiment newest = list.get(0);
            Instant last = list.stream().map(e -> e.getCompletedAt() == null ? e.getCreatedAt() : e.getCompletedAt())
                    .max(Comparator.naturalOrder()).orElse(null);
            out.add(new WorkloadGroup(key, newest.getAlgorithmId(), newest.getAlgorithmVersion(), dimensions(newest),
                    list.size(), modes, last, list.stream().limit(MAX_COMPARED).map(Experiment::getId).toList()));
        });
        return out;
    }

    /** An experiment with the statistics of its completed runs. */
    private record Measured(Experiment experiment, Map<String, Object> summary, Map<String, Object> parameters,
            List<ExperimentRun> runs, Map<Long, Map<String, Double>> metrics, Stats wall, Stats execution,
            Stats waiting) {

        Stats time(String basis) {
            return EXECUTION_TIME.equals(basis) ? execution : wall;
        }

        Map<String, Object> dimensions() {
            Object d = summary.get("dimensions");
            return d instanceof Map<?, ?> map ? castMap(map) : Map.of();
        }
    }

    private Measured measure(Experiment e, List<ExperimentRun> done, Map<Long, Map<String, Double>> metricsByRun) {
        Map<Long, Map<String, Double>> own = new LinkedHashMap<>();
        done.forEach(r -> own.put(r.getId(), metricsByRun.getOrDefault(r.getId(), Map.of())));
        double[] walls = done.stream().map(ExperimentRun::getWallTimeMs).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray();
        double[] executions = own.values().stream().map(v -> v.get("execution_time")).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray();
        double[] waits = done.stream().filter(r -> r.getSubmittedAt() != null && r.getStartedAt() != null)
                .mapToDouble(r -> Math.max(0, Duration.between(r.getSubmittedAt(), r.getStartedAt()).toNanos() / 1e6))
                .toArray();
        return new Measured(e, map(e.getInputSummary()), map(e.getParameters()), done, own, stats(walls),
                executions.length == walls.length ? stats(executions) : null, stats(waits));
    }

    private void checkCompatibility(List<Measured> measured, List<Issue> issues) {
        List<Measured> withRuns = measured.stream().filter(m -> m.wall != null).toList();
        measured.stream().filter(m -> m.wall == null).forEach(m -> issues.add(new Issue("WARNING", "NO_COMPLETED_RUNS",
                m.experiment.getName() + " has no completed run; it is listed but not compared.",
                List.of(m.experiment.getId()))));
        Set<String> algorithms = withRuns.stream().map(m -> m.experiment.getAlgorithmId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (algorithms.size() > 1) {
            issues.add(new Issue("ERROR", "ALGORITHM_DIFFERS", "The experiments run different algorithms ("
                    + String.join(", ", algorithms) + "); their times measure different work.", ids(withRuns)));
            return;
        }
        Set<String> versions = withRuns.stream().map(m -> m.experiment.getAlgorithmVersion())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (versions.size() > 1) {
            issues.add(new Issue("WARNING", "VERSION_DIFFERS", "Different versions of the algorithm were measured ("
                    + String.join(", ", versions) + "); an implementation change may explain part of a difference.",
                    ids(withRuns)));
        }
        Set<String> dims = withRuns.stream().map(m -> canonical(m.dimensions()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (dims.size() > 1) {
            issues.add(new Issue("ERROR", "DIMENSIONS_DIFFER", "The workloads have different sizes ("
                    + String.join("; ", dims) + "). Compare experiments of the same size, or read the times as a "
                    + "scaling series rather than a speedup.", ids(withRuns)));
        }
        Set<String> inputs = withRuns.stream().map(m -> m.experiment.getInputSource() + ":"
                + Objects.toString(m.experiment.getInputRef(), "")).collect(Collectors.toSet());
        if (inputs.size() > 1) {
            issues.add(new Issue("WARNING", "INPUT_DIFFERS", "The input data are not the same (generated, pasted or "
                    + "uploaded data differ), although the sizes match.", ids(withRuns)));
        }
        Set<String> keys = new LinkedHashSet<>();
        withRuns.forEach(m -> keys.addAll(m.parameters.keySet()));
        List<String> differing = new ArrayList<>();
        for (String key : keys) {
            Set<String> values = withRuns.stream().map(m -> canonical(m.parameters.get(key))).collect(Collectors.toSet());
            if (values.size() > 1 && !"input".equals(key)) {
                differing.add(key);
            }
        }
        if (!differing.isEmpty()) {
            issues.add(new Issue("WARNING", "PARAMETERS_DIFFER", "Parameters differ between the experiments: "
                    + String.join(", ", differing) + ". The differences are part of the measured times.",
                    ids(withRuns)));
        }
    }

    private void checkMeasurements(List<Measured> measured, Measured baseline, boolean comparable, String basis,
            List<Issue> issues) {
        for (Measured m : measured) {
            Stats time = m.time(basis);
            if (time == null) {
                continue;
            }
            if (time.count() == 1) {
                issues.add(new Issue("WARNING", "SINGLE_RUN", m.experiment.getName() + " has one completed run, so "
                        + "its variation is unknown. Repetitions make the comparison more reliable.",
                        List.of(m.experiment.getId())));
            } else if (time.cv() != null && time.cv() > HIGH_VARIATION) {
                issues.add(new Issue("WARNING", "HIGH_VARIATION", String.format(Locale.ROOT, "The times of %s vary "
                        + "by %.0f %% (coefficient of variation); differences smaller than that are not meaningful.",
                        m.experiment.getName(), time.cv() * 100), List.of(m.experiment.getId())));
            }
            Set<String> digests = m.runs.stream().map(ExperimentRun::getResultDigest).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (digests.size() > 1) {
                issues.add(new Issue("WARNING", "INCONSISTENT_RESULTS", "The runs of " + m.experiment.getName()
                        + " produced different results; each passed Core's verification, so the difference is within "
                        + "its tolerance (for example floating-point rounding).", List.of(m.experiment.getId())));
            }
        }
        if (comparable) {
            Set<String> digests = measured.stream().filter(m -> m.wall != null).flatMap(m -> m.runs.stream())
                    .map(ExperimentRun::getResultDigest).filter(Objects::nonNull).collect(Collectors.toSet());
            if (digests.size() == 1) {
                issues.add(new Issue("INFO", "RESULTS_IDENTICAL", "Every compared run produced the same verified "
                        + "result, so the experiments did the same work.", List.of()));
            } else if (digests.size() > 1) {
                issues.add(new Issue("WARNING", "RESULTS_DIFFER", "The verified results are not bit-identical across "
                        + "experiments. Each passed Core's verification; splitting floating-point work differently "
                        + "changes the rounding.", List.of()));
            }
            Stats base = baseline == null ? null : baseline.time(basis);
            if (base != null && base.median() < SMALL_WORKLOAD_MS) {
                issues.add(new Issue("INFO", "SMALL_WORKLOAD", String.format(Locale.ROOT, "The baseline takes %.1f ms: "
                        + "for workloads this small, fixed costs (scheduling, partitioning, communication) dominate and "
                        + "parallel execution rarely pays off.", base.median()),
                        List.of(baseline.experiment.getId())));
            }
            issues.add(new Issue("INFO", "UNCONTROLLED_LOAD", "Experiments are measured when they run; other load on "
                    + "the server and the workers at that time is not controlled.", List.of()));
        }
    }

    /**
     * Warns when compared runs executed while other runs (of any experiment) were executing: they shared processors
     * or workers, so their times are higher than on an otherwise idle system.
     */
    private void checkConcurrency(List<Measured> measured, List<Issue> issues) {
        int affectedRuns = 0;
        int comparedRuns = 0;
        List<String> affected = new ArrayList<>();
        Set<String> others = new LinkedHashSet<>();
        for (Measured m : measured) {
            List<ExperimentRun> timed = m.runs.stream()
                    .filter(r -> r.getStartedAt() != null && r.getCompletedAt() != null).toList();
            if (timed.isEmpty()) {
                continue;
            }
            Instant from = timed.stream().map(ExperimentRun::getStartedAt).min(Comparator.naturalOrder()).orElseThrow();
            Instant to = timed.stream().map(ExperimentRun::getCompletedAt).max(Comparator.naturalOrder()).orElseThrow();
            List<ExperimentRun> executing = runs.findExecutingBetween(from, to);
            boolean any = false;
            for (ExperimentRun r : timed) {
                comparedRuns++;
                List<ExperimentRun> overlapping = executing.stream().filter(o -> !o.getId().equals(r.getId()))
                        .filter(o -> o.getStartedAt().isBefore(r.getCompletedAt())
                                && o.getCompletedAt().isAfter(r.getStartedAt()))
                        .toList();
                if (!overlapping.isEmpty()) {
                    affectedRuns++;
                    any = true;
                    overlapping.forEach(o -> others.add(o.getExperimentId()));
                }
            }
            if (any) {
                affected.add(m.experiment.getId());
            }
        }
        if (affectedRuns > 0) {
            issues.add(new Issue("WARNING", "CONCURRENT_RUNS", String.format(Locale.ROOT, "%d of the %d compared runs "
                    + "executed while runs of %d other or compared experiment(s) were executing. They shared processors "
                    + "or workers, so their times are higher than on an idle system. For clean measurements, run "
                    + "experiments one after another.", affectedRuns, comparedRuns, others.size()), affected));
        }
    }

    /** Wall times include the wait before a run starts; say so when that wait is a noticeable share. */
    private static void checkWaiting(List<Measured> measured, String basis, List<Issue> issues) {
        if (!WALL_TIME.equals(basis)) {
            return;
        }
        List<Measured> waited = measured.stream().filter(m -> m.wall != null && m.waiting != null
                && m.waiting.median() > SIGNIFICANT_WAIT * m.wall.median()).toList();
        if (waited.isEmpty()) {
            return;
        }
        Measured worst = waited.stream().max(Comparator.comparingDouble(m -> m.waiting.median() / m.wall.median()))
                .orElseThrow();
        issues.add(new Issue("WARNING", "WAITING_INCLUDED", String.format(Locale.ROOT, "Wall times include the wait "
                + "before a run starts (validation and queueing behind other runs): up to %.0f %% of the wall time, "
                + "for %s. Compare by Core execution time to leave the wait out.",
                100 * worst.waiting.median() / worst.wall.median(), worst.experiment.getName()), ids(waited)));
    }

    private static Measured chooseBaseline(List<Measured> measured, String requested) {
        if (requested != null) {
            return measured.stream().filter(m -> m.experiment.getId().equals(requested)).findFirst().orElse(null);
        }
        return measured.stream().filter(m -> m.wall != null)
                .filter(m -> "SEQUENTIAL_CPU".equals(m.experiment.getExecutionMode()))
                .max(Comparator.comparingInt(m -> m.wall.count()))
                .or(() -> measured.stream().filter(m -> m.wall != null).findFirst())
                .orElse(null);
    }

    private Compared compared(Measured m, Measured baseline, boolean sequentialBaseline, String basis) {
        Experiment e = m.experiment;
        String mode = e.getExecutionMode();
        Integer threads = medianInt(m.runs.stream().map(ExperimentRun::getThreads).toList());
        Integer workers = medianInt(m.runs.stream().map(ExperimentRun::getWorkersUsed).toList());
        Integer partitions = medianInt(m.runs.stream().map(ExperimentRun::getPartitions).toList());
        Double speedup = null;
        List<Double> range = null;
        Double efficiency = null;
        String efficiencyBasis = null;
        Stats own = m.time(basis);
        Stats reference = baseline == null ? null : baseline.time(basis);
        if (reference != null && own != null) {
            double base = reference.median();
            speedup = base / own.median();
            range = List.of(base / own.max(), base / own.min());
            if (sequentialBaseline && "MULTITHREADED_CPU".equals(mode) && threads != null && threads > 0) {
                efficiency = speedup / threads;
                efficiencyBasis = "thread";
            } else if (sequentialBaseline && "DISTRIBUTED_CPU".equals(mode) && workers != null && workers > 0) {
                efficiency = speedup / workers;
                efficiencyBasis = "worker";
            }
        }
        Map<String, Double> breakdown = breakdown(m);
        Map<String, Double> indicators = new LinkedHashMap<>();
        for (String name : INDICATORS) {
            Double value = metricMedian(m, name);
            if (value != null) {
                indicators.put(name, value);
            }
        }
        Set<String> digests = m.runs.stream().map(ExperimentRun::getResultDigest).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Number operations = m.summary.get("estimatedOperations") instanceof Number n ? n : null;
        return new Compared(e.getId(), e.getName(), e.getOwner().getUsername(), e.getAlgorithmId(),
                e.getAlgorithmVersion(), mode, e.getStatus().name(), e.getInputSource().name(), m.dimensions(),
                operations == null ? null : operations.doubleValue(), (String) m.summary.get("operationUnit"),
                m.parameters, e.getRepetitions(), m.runs.size(), "SEQUENTIAL_CPU".equals(mode) ? 1 : threads,
                "DISTRIBUTED_CPU".equals(mode) ? workers : null, partitions, e.getSchedulingPolicy(), m.wall,
                m.execution, m.waiting,
                m.runs.stream().filter(r -> r.getWallTimeMs() != null)
                        .map(r -> new RunPoint(r.getRunIndex(), r.getWallTimeMs(), r.getResultDigest())).toList(),
                speedup, range, efficiency, efficiencyBasis, breakdown, indicators,
                digests.size() == 1 ? digests.iterator().next() : null, digests.size() <= 1);
    }

    /** Median milliseconds per Core phase, and the rest of the wall time as "other". */
    private static Map<String, Double> breakdown(Measured m) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (m.runs.isEmpty()) {
            return out;
        }
        if (m.waiting != null) {
            out.put("waiting", m.waiting.median());
        }
        for (String phase : PHASES) {
            Double value = metricMedian(m, phase);
            if (value != null) {
                out.put(phase, value);
            }
        }
        double[] rest = m.runs.stream().filter(r -> r.getWallTimeMs() != null).mapToDouble(r -> {
            Map<String, Double> values = m.metrics.getOrDefault(r.getId(), Map.of());
            double phases = PHASES.stream().mapToDouble(p -> values.getOrDefault(p, 0.0)).sum();
            double waited = r.getSubmittedAt() == null || r.getStartedAt() == null ? 0
                    : Math.max(0, Duration.between(r.getSubmittedAt(), r.getStartedAt()).toNanos() / 1e6);
            return Math.max(0, r.getWallTimeMs() - waited - phases);
        }).sorted().toArray();
        if (rest.length > 0 && !out.isEmpty()) {
            out.put("other", median(rest));
        }
        return out;
    }

    private static Double metricMedian(Measured m, String name) {
        Stats s = metricStats(m, name);
        return s == null ? null : s.median();
    }

    private static Stats metricStats(Measured m, String name) {
        return stats(m.metrics.values().stream().map(v -> v.get(name)).filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue).toArray());
    }

    private List<String> summary(List<Compared> compared, boolean comparable, Measured baseline, String basis) {
        List<String> out = new ArrayList<>();
        List<Compared> measuredOnes = compared.stream().filter(c -> c.wallTime() != null).toList();
        String what = EXECUTION_TIME.equals(basis) ? "Core execution time" : "wall time";
        if (measuredOnes.isEmpty()) {
            out.add("None of the experiments has a completed run yet.");
            return out;
        }
        if (!comparable || baseline == null) {
            out.add("The experiments do not run the same workload, so their times are listed but not compared.");
            return out;
        }
        String baseName = baseline.experiment.getName();
        for (Compared c : measuredOnes) {
            if (c.id().equals(baseline.experiment.getId()) || c.speedup() == null) {
                continue;
            }
            String runsText = String.format(Locale.ROOT, "median %s of %d and %d runs", what,
                    c.wallTime().count(), baseline.wall.count());
            if (c.speedup() >= 1.05) {
                out.add(String.format(Locale.ROOT, "%s (%s) was %.2f× as fast as %s (%s).", c.name(), label(c.mode()),
                        c.speedup(), baseName, runsText));
            } else if (c.speedup() <= 0.95) {
                out.add(String.format(Locale.ROOT, "%s (%s) took %.2f× the time of %s (%s).", c.name(),
                        label(c.mode()), 1 / c.speedup(), baseName, runsText));
            } else {
                out.add(String.format(Locale.ROOT, "%s (%s) and %s took about the same time (%s).", c.name(),
                        label(c.mode()), baseName, runsText));
            }
        }
        Function<Compared, Stats> time = c -> EXECUTION_TIME.equals(basis) ? c.executionTime() : c.wallTime();
        measuredOnes.stream().filter(c -> time.apply(c) != null)
                .min(Comparator.comparingDouble(c -> time.apply(c).median())).ifPresent(f -> out.add(
                        String.format(Locale.ROOT, "Fastest: %s (%s), median %s %.1f ms.", f.name(), label(f.mode()),
                                what, time.apply(f).median())));
        return out;
    }

    static String label(String mode) {
        return switch (mode) {
            case "SEQUENTIAL_CPU" -> "sequential CPU";
            case "MULTITHREADED_CPU" -> "parallel CPU, threads";
            case "DISTRIBUTED_CPU" -> "distributed CPU, workers";
            default -> mode;
        };
    }

    /**
     * Descriptive statistics.
     *
     * @param values values in any order
     * @return statistics, or null without values
     */
    static Stats stats(double[] values) {
        if (values.length == 0) {
            return null;
        }
        double[] v = values.clone();
        Arrays.sort(v);
        double mean = Arrays.stream(v).average().orElse(0);
        double var = v.length < 2 ? 0 : Arrays.stream(v).map(x -> (x - mean) * (x - mean)).sum() / (v.length - 1);
        double sd = Math.sqrt(var);
        return new Stats(v.length, median(v), mean, v[0], v[v.length - 1], sd,
                v.length < 2 || mean == 0 ? null : sd / mean);
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private static Integer medianInt(Collection<Integer> values) {
        double[] v = values.stream().filter(Objects::nonNull).mapToDouble(Integer::doubleValue).sorted().toArray();
        return v.length == 0 ? null : (int) Math.round(median(v));
    }

    private Map<String, Object> dimensions(Experiment e) {
        Object d = map(e.getInputSummary()).get("dimensions");
        return d instanceof Map<?, ?> m ? castMap(m) : Map.of();
    }

    private Map<String, Object> map(String value) {
        return value == null || value.isBlank() ? Map.of() : json.map(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static List<String> ids(List<Measured> measured) {
        return measured.stream().map(m -> m.experiment.getId()).toList();
    }

    /** A canonical text of a JSON value: keys sorted, numbers without trailing zeros. */
    static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new TreeMap<>(map.entrySet().stream().collect(Collectors.toMap(en -> String.valueOf(en.getKey()),
                    en -> canonical(en.getValue())))).entrySet().stream()
                    .map(en -> en.getKey() + " " + en.getValue()).collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AnalysisService::canonical).collect(Collectors.joining(",", "[", "]"));
        }
        if (value instanceof Number n) {
            try {
                return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ex) {
                return n.toString();
            }
        }
        return String.valueOf(value);
    }
}
