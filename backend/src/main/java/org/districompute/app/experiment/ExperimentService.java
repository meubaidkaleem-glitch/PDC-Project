package org.districompute.app.experiment;

import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.districompute.app.advisor.AdviceView;
import org.districompute.app.advisor.AdvisorService;
import org.districompute.app.api.ApiException;
import org.districompute.app.api.PageResponse;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.execution.ExecutionService;
import org.districompute.app.execution.ResultStore;
import org.districompute.app.experiment.ExperimentViews.CreateExperiment;
import org.districompute.app.experiment.ExperimentViews.ExperimentDetail;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.experiment.ExperimentViews.LiveAttempt;
import org.districompute.app.experiment.ExperimentViews.LivePartition;
import org.districompute.app.experiment.ExperimentViews.LiveRun;
import org.districompute.app.experiment.ExperimentViews.MetricStats;
import org.districompute.app.experiment.ExperimentViews.MetricValue;
import org.districompute.app.experiment.ExperimentViews.ResultView;
import org.districompute.app.experiment.ExperimentViews.RunView;
import org.districompute.app.monitoring.ExperimentDirectory;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.Enums.AdviceBasis;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentResult;
import org.districompute.app.persistence.ExperimentResultRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.persistence.PartitionTask;
import org.districompute.app.persistence.PartitionTaskRepository;
import org.districompute.app.persistence.RunMetric;
import org.districompute.app.persistence.RunMetricRepository;
import org.districompute.app.security.AuditService;
import org.districompute.app.task.TaskDraft;
import org.districompute.app.task.StoredTasks;
import org.districompute.app.task.TaskFactory;
import org.districompute.app.task.TaskFactory.PreparedTask;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.TaskSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Experiment lifecycle and queries ({@code docs/api.md#experiments}). */
@Service
public class ExperimentService {

    private static final Logger LOG = LoggerFactory.getLogger(ExperimentService.class);
    private static final Set<ExperimentStatus> ACTIVE = Set.of(ExperimentStatus.QUEUED, ExperimentStatus.RUNNING);

    private final TaskFactory tasks;
    private final StoredTasks storedTasks;
    private final AdvisorService advisor;
    private final ExecutionService execution;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final ExperimentResultRepository results;
    private final RunMetricRepository metrics;
    private final PartitionTaskRepository partitions;
    private final ResultStore resultStore;
    private final ExperimentDirectory directory;
    private final SubmissionLimiter limiter;
    private final AuditService audit;
    private final Coordinator coordinator;
    private final JsonSupport json;
    private final AppProperties.Limits limits;
    private final Clock clock;

    ExperimentService(TaskFactory tasks, StoredTasks storedTasks, AdvisorService advisor, ExecutionService execution,
            ExperimentRepository experiments, ExperimentRunRepository runs, ExperimentResultRepository results,
            RunMetricRepository metrics, PartitionTaskRepository partitions, ResultStore resultStore,
            ExperimentDirectory directory, SubmissionLimiter limiter, AuditService audit, Coordinator coordinator,
            JsonSupport json, AppProperties properties, Clock clock) {
        this.tasks = tasks;
        this.storedTasks = storedTasks;
        this.advisor = advisor;
        this.execution = execution;
        this.experiments = experiments;
        this.runs = runs;
        this.results = results;
        this.metrics = metrics;
        this.partitions = partitions;
        this.resultStore = resultStore;
        this.directory = directory;
        this.limiter = limiter;
        this.audit = audit;
        this.coordinator = coordinator;
        this.json = json;
        this.limits = properties.limits();
        this.clock = clock;
    }

    /**
     * Creates an experiment and starts it after the transaction commits. The current recommendation is computed
     * again and recorded together with whether the user followed it.
     *
     * @param request the experiment
     * @param owner the user
     * @param admin whether the user is an administrator
     * @param batchId batch, or {@code null}
     * @return the experiment
     */
    @Transactional
    public ExperimentDetail create(CreateExperiment request, AppUser owner, boolean admin, String batchId) {
        if (batchId != null) {
            throw new IllegalArgumentException("Batch experiments are created with createInBatch");
        }
        checkActiveLimit(owner);
        limiter.acquire(owner.getId(), 1);
        Experiment experiment = persist(request, owner, admin, null, null);
        String id = experiment.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                execution.start(id);
            }
        });
        return detail(experiment);
    }

    /**
     * Creates a queued experiment of a batch without starting it; the batch starts its experiments one after
     * another ({@code docs/batches-and-templates.md}). The caller checks the limits for the whole batch.
     *
     * @param request the experiment
     * @param owner the user
     * @param admin whether the user is an administrator
     * @param batchId the batch
     * @param position its position in the batch, from 1
     * @return the experiment
     */
    @Transactional
    public Experiment createInBatch(CreateExperiment request, AppUser owner, boolean admin, String batchId,
            int position) {
        return persist(request, owner, admin, batchId, position);
    }

    /**
     * Rejects a new submission when the user already has as many active experiments as allowed. A batch with
     * queued or running experiments counts as one, because it runs one experiment at a time.
     *
     * @param owner the user
     */
    public void checkActiveLimit(AppUser owner) {
        long active = experiments.countByOwnerIdAndBatchIdIsNullAndStatusIn(owner.getId(), ACTIVE)
                + experiments.countActiveBatches(owner.getId(), ACTIVE);
        if (active >= limits.maxActiveExperimentsPerUser()) {
            throw ApiException.tooManyRequests("You already have " + active + " queued or running experiments or "
                    + "batches (limit " + limits.maxActiveExperimentsPerUser() + "); wait for one to finish");
        }
    }

    private Experiment persist(CreateExperiment request, AppUser owner, boolean admin, String batchId,
            Integer position) {
        PreparedTask task = tasks.prepare(request.task(), owner, admin);
        String recommended = null;
        AdviceBasis basis = AdviceBasis.NONE;
        try {
            AdviceView advice = advisor.advise(task);
            recommended = advice.recommendedMode();
            basis = AdviceBasis.valueOf(advice.basis());
        } catch (RuntimeException e) {
            LOG.warn("Could not compute the recommendation for a new experiment", e);
        }
        Experiment experiment = new Experiment(UUID.randomUUID().toString(), owner, clock.instant());
        experiment.setName(request.name().strip());
        experiment.setDescription(request.description());
        experiment.setBatchId(batchId);
        experiment.setBatchPosition(position);
        experiment.setCategory(task.category().name());
        experiment.setAlgorithmId(task.info().descriptor().id().value());
        experiment.setAlgorithmVersion(task.algorithmVersion());
        experiment.setParameters(json.write(task.storedParameters()));
        experiment.setInputSource(task.inputSource());
        experiment.setInputSummary(json.write(inputSummary(task)));
        experiment.setExecutionMode(task.mode().name());
        experiment.setRecommendedMode(recommended);
        experiment.setRecommendationAccepted(recommended == null ? null : recommended.equals(task.mode().name()));
        if (recommended != null && !recommended.equals(task.mode().name())) {
            experiment.setOverrideReason(request.overrideReason() == null || request.overrideReason().isBlank()
                    ? "No reason given" : request.overrideReason().strip());
        }
        experiment.setAdviceBasis(basis);
        experiment.setSchedulingPolicy(task.settings().schedulingPolicy());
        experiment.setConfiguration(json.write(task.settings()));
        experiment.setRepetitions(task.settings().repetitionCount());
        experiments.save(experiment);
        for (int i = 1; i <= experiment.getRepetitions(); i++) {
            runs.save(new ExperimentRun(experiment.getId(), i, task.mode().name()));
        }
        audit.record(owner.getUsername(), "EXPERIMENT_CREATED", "EXPERIMENT", experiment.getId(),
                experiment.getAlgorithmId() + " " + experiment.getExecutionMode() + " x" + experiment.getRepetitions()
                        + (batchId == null ? "" : " in batch " + batchId), null);
        return experiment;
    }

    /**
     * A new experiment with the same configuration.
     *
     * @param id experiment to repeat
     * @param owner the user
     * @param admin whether the user is an administrator
     * @param access ownership check
     * @return the new experiment
     */
    @Transactional
    public ExperimentDetail rerun(String id, AppUser owner, boolean admin, ExperimentAccess access) {
        Experiment source = access.require(id);
        TaskDraft draft = draftOf(source);
        String name = source.getName().endsWith("(re-run)") ? source.getName() : source.getName() + " (re-run)";
        return create(new CreateExperiment(name.length() > 200 ? name.substring(0, 200) : name,
                source.getDescription(), draft, source.getOverrideReason()), owner, admin, null);
    }

    /**
     * The draft an experiment was created from.
     *
     * @param experiment the experiment
     * @return the draft
     */
    public TaskDraft draftOf(Experiment experiment) {
        return storedTasks.draftOf(experiment);
    }

    /**
     * Experiments visible to the user, filtered and paged.
     *
     * @param filter filters
     * @param pageable page and sort
     * @param user the user
     * @param admin whether the user sees everyone's experiments
     * @return the page
     */
    @Transactional(readOnly = true)
    public PageResponse<ExperimentSummary> list(ExperimentFilter filter, Pageable pageable, AppUser user,
            boolean admin) {
        Specification<Experiment> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (!admin || filter.mine()) {
                where.add(cb.equal(root.get("owner").get("id"), user.getId()));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                String like = "%" + filter.search().strip().toLowerCase().replace("%", "\\%").replace("_", "\\_")
                        + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("id")), like),
                        cb.like(cb.lower(root.get("algorithmId")), like)));
            }
            if (filter.status() != null) {
                where.add(cb.equal(root.get("status"), ExperimentStatus.valueOf(filter.status())));
            }
            if (filter.algorithm() != null) {
                where.add(cb.equal(root.get("algorithmId"), filter.algorithm()));
            }
            if (filter.mode() != null) {
                where.add(cb.equal(root.get("executionMode"), filter.mode()));
            }
            if (filter.category() != null) {
                where.add(cb.equal(root.get("category"), filter.category()));
            }
            if (filter.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.batchId() != null) {
                where.add(cb.equal(root.get("batchId"), filter.batchId()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<Experiment> page = experiments.findAll(spec, pageable);
        Map<String, List<ExperimentRun>> byExperiment = runsOf(page.getContent());
        return PageResponse.of(page, e -> summary(e, byExperiment.getOrDefault(e.getId(), List.of())));
    }

    /**
     * Everything about an experiment.
     *
     * @param experiment the experiment (access already checked)
     * @return the detail
     */
    @Transactional(readOnly = true)
    public ExperimentDetail detail(Experiment experiment) {
        List<ExperimentRun> experimentRuns = runs.findByExperimentIdOrderByRunIndexAsc(experiment.getId());
        Map<Long, Integer> indexOf = experimentRuns.stream().collect(Collectors.toMap(ExperimentRun::getId,
                ExperimentRun::getRunIndex));
        List<ResultView> resultViews = results.findByExperimentIdOrderByRunIdAsc(experiment.getId()).stream()
                .map(r -> resultView(r, indexOf.getOrDefault(r.getRunId(), 0))).toList();
        return new ExperimentDetail(summary(experiment, experimentRuns), experiment.getDescription(),
                json.map(experiment.getParameters()), experiment.getInputSource().name(),
                json.map(experiment.getInputSummary()), json.map(experiment.getConfiguration()),
                experiment.getAdviceBasis() == null ? null : experiment.getAdviceBasis().name(),
                experiment.getOverrideReason(), experiment.getErrorCode(),
                experimentRuns.stream().map(ExperimentService::runView).toList(), resultViews,
                stats(metrics.findByExperimentIdOrderByRunIdAscMetricNameAsc(experiment.getId()), experimentRuns));
    }

    /**
     * Every metric value of an experiment.
     *
     * @param experiment the experiment
     * @return values by run
     */
    @Transactional(readOnly = true)
    public List<MetricValue> metricValues(Experiment experiment) {
        Map<Long, Integer> indexOf = runs.findByExperimentIdOrderByRunIndexAsc(experiment.getId()).stream()
                .collect(Collectors.toMap(ExperimentRun::getId, ExperimentRun::getRunIndex));
        return metrics.findByExperimentIdOrderByRunIdAscMetricNameAsc(experiment.getId()).stream()
                .map(m -> new MetricValue(indexOf.getOrDefault(m.getRunId(), 0), m.getMetricName(), m.getMetricValue(),
                        m.getUnit(), m.getSource().name())).toList();
    }

    /**
     * Deletes a finished experiment with its runs, events and stored results.
     *
     * @param experiment the experiment
     * @param username acting user
     */
    @Transactional
    public void delete(Experiment experiment, String username) {
        if (!experiment.getStatus().isTerminal()) {
            throw ApiException.conflict("EXPERIMENT_ACTIVE", "Cancel the experiment before deleting it");
        }
        String id = experiment.getId();
        experiments.delete(experiment);
        directory.forget(id);
        audit.record(username, "EXPERIMENT_DELETED", "EXPERIMENT", id, experiment.getName(), null);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                resultStore.deleteAll(id);
            }
        });
    }

    /**
     * The runtime's current view of an experiment's latest run.
     *
     * @param experiment the experiment
     * @param runIndex run to show, or {@code null} for the active or latest one
     * @return the live view
     */
    @Transactional(readOnly = true)
    public LiveRun live(Experiment experiment, Integer runIndex) {
        List<ExperimentRun> experimentRuns = runs.findByExperimentIdOrderByRunIndexAsc(experiment.getId());
        if (experimentRuns.isEmpty()) {
            throw ApiException.notFound("Run of experiment", experiment.getId());
        }
        ExperimentRun run;
        if (runIndex != null) {
            run = experimentRuns.stream().filter(r -> r.getRunIndex() == runIndex).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Run", runIndex));
        } else {
            String active = execution.activeRun(experiment.getId()).orElse(null);
            run = experimentRuns.stream().filter(r -> r.getCoreTaskId().equals(active)).findFirst()
                    .orElseGet(() -> experimentRuns.stream().filter(r -> r.getStatus() != RunStatus.PENDING)
                            .reduce((a, b) -> b).orElse(experimentRuns.get(0)));
        }
        TaskSnapshot snapshot = coordinator.task(run.getCoreTaskId()).orElse(null);
        if (snapshot == null) {
            return storedLive(experiment, run);
        }
        List<LivePartition> livePartitions = snapshot.partitions().stream().map(p -> new LivePartition(p.index(),
                p.partitionId(), p.status().name(), p.attempts().stream().map(ExperimentService::liveAttempt)
                        .toList())).toList();
        int failed = (int) livePartitions.stream().filter(p -> "FAILED".equals(p.status())).count();
        int running = (int) snapshot.partitions().stream().filter(p -> p.attempts().stream()
                .anyMatch(a -> !a.status().isTerminal())).count();
        int pending = Math.max(0, snapshot.totalPartitions() - snapshot.completedPartitions() - failed - running);
        List<String> activeWorkers = snapshot.partitions().stream().flatMap(p -> p.attempts().stream())
                .filter(a -> !a.status().isTerminal() && a.workerId() != null).map(AttemptSnapshot::workerId)
                .distinct().sorted().toList();
        List<String> errors = new ArrayList<>();
        snapshot.partitions().stream().flatMap(p -> p.attempts().stream()).filter(a -> a.failure() != null)
                .forEach(a -> errors.add(a.attemptId() + " on " + a.workerId() + ": " + a.failure()));
        if (snapshot.error() != null) {
            errors.add(snapshot.error().code() + ": " + snapshot.error().message());
        }
        return new LiveRun(experiment.getId(), experiment.getStatus().name(), run.getRunIndex(), run.getCoreTaskId(),
                true, snapshot.status().name(), snapshot.mode().name(), snapshot.algorithm().id().value(),
                snapshot.policy() == null ? null : snapshot.policy().name(), snapshot.submittedAt(),
                snapshot.finishedAt(), snapshot.totalPartitions(), snapshot.completedPartitions(), failed, pending,
                running, snapshot.attempts(), snapshot.retries(), activeWorkers, livePartitions,
                snapshot.metrics() == null ? Map.of() : json.toMap(snapshot.metrics()), errors);
    }

    private LiveRun storedLive(Experiment experiment, ExperimentRun run) {
        List<PartitionTask> stored = partitions.findByRunIdOrderByPartitionIndexAsc(run.getId());
        List<LivePartition> livePartitions = stored.stream().map(p -> new LivePartition(p.getPartitionIndex(),
                p.getPartitionId(), p.getStatus(), p.getWorkerId() == null ? List.of()
                        : List.of(new LiveAttempt(null, p.getWorkerId(), p.getStatus(), p.getAssignedAt(),
                                p.getStartedAt(), p.getCompletedAt(), p.getErrorMessage(), null, 0, 0, 0)))).toList();
        int completed = (int) stored.stream().filter(p -> "COMPLETED".equals(p.getStatus())).count();
        int failed = (int) stored.stream().filter(p -> "FAILED".equals(p.getStatus())).count();
        int total = run.getPartitions() == null ? stored.size() : run.getPartitions();
        List<String> errors = run.getErrorMessage() == null ? List.of() : List.of(run.getErrorMessage());
        return new LiveRun(experiment.getId(), experiment.getStatus().name(), run.getRunIndex(), run.getCoreTaskId(),
                false, run.getStatus().name(), run.getExecutionMode(), experiment.getAlgorithmId(),
                experiment.getSchedulingPolicy(), run.getSubmittedAt(), run.getCompletedAt(), total,
                run.getStatus() == RunStatus.COMPLETED && stored.isEmpty() ? total : completed, failed,
                0, 0, stored.stream().mapToInt(PartitionTask::getAttemptCount).sum(), 0, List.of(), livePartitions,
                Map.of(), errors);
    }

    private static LiveAttempt liveAttempt(AttemptSnapshot a) {
        return new LiveAttempt(a.attemptId(), a.workerId(), a.status().name(), a.dispatchedAt(), a.acceptedAt(),
                a.finishedAt(), a.failure(), a.assignment() == null ? null : a.assignment().explanation(),
                a.transferNanos() / 1e6, a.computeNanos() / 1e6, a.bytesSent() + a.bytesReceived());
    }

    Map<String, List<ExperimentRun>> runsOf(List<Experiment> list) {
        if (list.isEmpty()) {
            return Map.of();
        }
        return runs.findByExperimentIdIn(list.stream().map(Experiment::getId).toList()).stream()
                .collect(Collectors.groupingBy(ExperimentRun::getExperimentId));
    }

    /**
     * The list view of an experiment.
     *
     * @param e experiment
     * @param experimentRuns its runs
     * @return the summary
     */
    public ExperimentSummary summary(Experiment e, List<ExperimentRun> experimentRuns) {
        List<ExperimentRun> done = experimentRuns.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED).toList();
        double[] walls = done.stream().filter(r -> r.getWallTimeMs() != null).mapToDouble(ExperimentRun::getWallTimeMs)
                .sorted().toArray();
        Integer workers = experimentRuns.stream().map(ExperimentRun::getWorkersUsed).filter(w -> w != null)
                .max(Comparator.naturalOrder()).orElse(null);
        Long duration = e.getStartedAt() == null || e.getCompletedAt() == null ? null
                : Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis();
        return new ExperimentSummary(e.getId(), e.getName(), e.getOwner().getUsername(), e.getCategory(),
                e.getAlgorithmId(), e.getAlgorithmVersion(), e.getExecutionMode(), e.getRecommendedMode(),
                e.getRecommendationAccepted(), e.getSchedulingPolicy(), e.getStatus().name(), e.getRepetitions(),
                done.size(), workers, walls.length == 0 ? null : median(walls), e.getCreatedAt(), e.getStartedAt(),
                e.getCompletedAt(), duration, e.getErrorMessage(), e.getBatchId());
    }

    static RunView runView(ExperimentRun r) {
        return new RunView(r.getId(), r.getRunIndex(), r.getCoreTaskId(), r.getExecutionMode(), r.getStatus().name(),
                r.getThreads(), r.getPartitions(), r.getWorkersUsed(), r.getSubmittedAt(), r.getStartedAt(),
                r.getCompletedAt(), r.getWallTimeMs(), r.getResultDigest(), r.getVerificationPassed(),
                r.getVerificationMethod(), r.getErrorCode(), r.getErrorMessage());
    }

    ResultView resultView(ExperimentResult r, int runIndex) {
        return new ResultView(runIndex, r.getResultType(), json.map(r.getSummary()), r.getDigest(),
                r.getValidationStatus().name(), r.getValidationMethod(), r.getValidationMessage(),
                r.getStorageRef() != null, r.getStorageFormat() == null ? null : r.getStorageFormat().name(),
                r.getSizeBytes(), r.getStoredDigestMatch(), r.getStorageNote());
    }

    /**
     * Statistics of every metric over the completed runs of an experiment.
     *
     * @param values metric rows
     * @param experimentRuns the runs
     * @return one entry per metric and source
     */
    public static List<MetricStats> stats(List<RunMetric> values, List<ExperimentRun> experimentRuns) {
        Set<Long> completed = experimentRuns.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED)
                .map(ExperimentRun::getId).collect(Collectors.toSet());
        Map<String, List<RunMetric>> grouped = values.stream().filter(m -> completed.contains(m.getRunId()))
                .collect(Collectors.groupingBy(m -> m.getMetricName() + "|" + m.getSource(), LinkedHashMap::new,
                        Collectors.toList()));
        List<MetricStats> out = new ArrayList<>();
        grouped.forEach((key, list) -> {
            double[] v = list.stream().mapToDouble(RunMetric::getMetricValue).sorted().toArray();
            double mean = java.util.Arrays.stream(v).average().orElse(0);
            double var = v.length < 2 ? 0 : java.util.Arrays.stream(v).map(x -> (x - mean) * (x - mean)).sum()
                    / (v.length - 1);
            out.add(new MetricStats(list.get(0).getMetricName(), list.get(0).getUnit(), list.get(0).getSource().name(),
                    v.length, median(v), mean, v[0], v[v.length - 1], Math.sqrt(var)));
        });
        out.sort(Comparator.comparing(MetricStats::name));
        return out;
    }

    static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private Map<String, Object> inputSummary(PreparedTask task) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dimensions", task.profile().dimensions());
        summary.put("estimatedOperations", task.profile().estimatedOperations());
        summary.put("operationUnit", task.profile().operationUnit());
        summary.put("inputBytes", task.profile().inputBytes());
        summary.put("outputBytes", task.profile().outputBytes());
        summary.put("estimatedPeakMemoryBytes", task.profile().estimatedPeakMemoryBytes());
        if (!task.uploads().isEmpty()) {
            Map<String, Object> uploads = new LinkedHashMap<>();
            task.uploads().forEach((parameter, meta) -> uploads.put(parameter, Map.of("id", meta.id(), "fileName",
                    meta.fileName(), "rows", meta.rows(), "columns", meta.columns(), "values", meta.count(), "bytes",
                    meta.bytes(), "sha256", meta.sha256())));
            summary.put("uploads", uploads);
        }
        return summary;
    }

    /**
     * Filters of the experiment list.
     *
     * @param search text in the name, id or algorithm
     * @param status status
     * @param algorithm algorithm id
     * @param mode execution mode
     * @param category application category
     * @param from created at or after
     * @param to created before
     * @param batchId batch
     * @param mine only the user's own (administrators)
     */
    public record ExperimentFilter(String search, String status, String algorithm, String mode, String category,
            Instant from, Instant to, String batchId, boolean mine) {
    }
}
