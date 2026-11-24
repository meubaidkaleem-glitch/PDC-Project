package org.districompute.app.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.monitoring.ApplicationEvents;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.RunStatus;
import org.districompute.app.persistence.Enums.Severity;
import org.districompute.app.persistence.Enums.ValidationStatus;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentResult;
import org.districompute.app.persistence.ExperimentResultRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.persistence.PartitionTask;
import org.districompute.app.persistence.PartitionTaskRepository;
import org.districompute.app.persistence.RunMetricRepository;
import org.districompute.app.task.ExecutionSettings;
import org.districompute.app.task.TaskDraft;
import org.districompute.app.task.TaskFactory;
import org.districompute.app.task.TaskFactory.PreparedTask;
import org.districompute.core.DistriComputeException;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.StatusChange;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.TaskHandle;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.PartitionSnapshot;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs experiments through the embedded coordinator: one run (Core task) at a time per experiment, repetitions in
 * order. HTTP threads only call {@link #start}; submission, completion and recording happen on the runtime's threads
 * and on this service's executor ({@code docs/architecture.md §3}).
 */
@Service
public class ExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionService.class);

    private final Coordinator coordinator;
    private final TaskFactory tasks;
    private final ResultStore results;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final PartitionTaskRepository partitions;
    private final RunMetricRepository metrics;
    private final ExperimentResultRepository resultRecords;
    private final ApplicationEvents events;
    private final JsonSupport json;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final Clock clock;
    /** Core task id of the run currently submitted for each experiment. */
    private final Map<String, String> active = new ConcurrentHashMap<>();
    private final Set<String> cancelRequested = ConcurrentHashMap.newKeySet();
    /** Experiments started and not yet finished (between start() and finishExperiment()). */
    private final Set<String> started = ConcurrentHashMap.newKeySet();
    /** True while startup recovery marks interrupted experiments as failed; batches do not continue then. */
    private volatile boolean recovering;
    /**
     * Locks that serialise the state changes of one experiment (start of a run, its completion, cancellation), so
     * that two threads never update the same experiment and its runs at once.
     */
    private final Object[] stripes = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();

    ExecutionService(Coordinator coordinator, TaskFactory tasks, ResultStore results, ExperimentRepository experiments,
            ExperimentRunRepository runs, PartitionTaskRepository partitions, RunMetricRepository metrics,
            ExperimentResultRepository resultRecords, ApplicationEvents events, JsonSupport json,
            TransactionTemplate tx, @Qualifier("executionExecutor") Executor executor, Clock clock) {
        this.coordinator = coordinator;
        this.tasks = tasks;
        this.results = results;
        this.experiments = experiments;
        this.runs = runs;
        this.partitions = partitions;
        this.metrics = metrics;
        this.resultRecords = resultRecords;
        this.events = events;
        this.json = json;
        this.tx = tx;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Starts an experiment's next pending run (asynchronously). Call after the experiment is committed.
     *
     * @param experimentId experiment
     */
    public void start(String experimentId) {
        if (started.add(experimentId)) {
            executor.execute(() -> startNextRun(experimentId));
        }
    }

    /**
     * Cancels an experiment: the running run is cancelled in the runtime, pending runs are not started.
     *
     * @param experimentId experiment
     * @return {@code true} if the experiment was still active
     */
    public boolean cancel(String experimentId) {
        synchronized (lockFor(experimentId)) {
            Experiment experiment = experiments.findById(experimentId).orElse(null);
            if (experiment == null || experiment.getStatus().isTerminal()) {
                return false;
            }
            cancelRequested.add(experimentId);
            String coreTaskId = active.get(experimentId);
            if (coreTaskId != null && coordinator.cancel(coreTaskId)) {
                return true; // the completion handler records CANCELLED
            }
            if (coreTaskId == null) {
                finishExperiment(experimentId, ExperimentStatus.CANCELLED, "CANCELLED", "Cancelled by the user");
            }
            return true;
        }
    }

    /**
     * Whether an experiment has a run in the runtime now.
     *
     * @param experimentId experiment
     * @return the Core task id of the active run
     */
    public Optional<String> activeRun(String experimentId) {
        return Optional.ofNullable(active.get(experimentId));
    }

    /**
     * Marks experiments left running by a previous process as failed: the coordinator's state did not survive the
     * restart, so their runs cannot be resumed ({@code docs/fault-tolerance.md}).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        List<Experiment> stale = experiments.findByStatusInOrderByCreatedAtAsc(
                List.of(ExperimentStatus.QUEUED, ExperimentStatus.RUNNING));
        recovering = true;
        try {
            recover(stale);
        } finally {
            recovering = false;
        }
    }

    private void recover(List<Experiment> stale) {
        for (Experiment e : stale) {
            LOG.warn("Experiment {} was {} when the application stopped; marking it FAILED", e.getId(), e.getStatus());
            transaction(s -> {
                for (ExperimentRun run : runs.findByExperimentIdOrderByRunIndexAsc(e.getId())) {
                    if (!run.getStatus().isTerminal()) {
                        run.setStatus(RunStatus.FAILED);
                        run.setErrorCode("INTERRUPTED");
                        run.setErrorMessage("The application restarted before this run finished");
                        run.setCompletedAt(clock.instant());
                        runs.save(run);
                    }
                }
            });
            finishExperiment(e.getId(), ExperimentStatus.FAILED, "INTERRUPTED",
                    "The application restarted while the experiment was " + e.getStatus().name().toLowerCase()
                            + "; its runs were lost with the runtime's in-memory state. Run it again to repeat it.");
        }
    }

    private void startNextRun(String experimentId) {
        synchronized (lockFor(experimentId)) {
            startNextRunLocked(experimentId);
        }
    }

    private void startNextRunLocked(String experimentId) {
        try {
            if (cancelRequested.contains(experimentId)) {
                finishExperiment(experimentId, ExperimentStatus.CANCELLED, "CANCELLED", "Cancelled by the user");
                return;
            }
            Experiment experiment = experiments.findById(experimentId).orElse(null);
            if (experiment == null || experiment.getStatus().isTerminal()) {
                return;
            }
            ExperimentRun run = runs.findByExperimentIdOrderByRunIndexAsc(experimentId).stream()
                    .filter(r -> r.getStatus() == RunStatus.PENDING).findFirst().orElse(null);
            if (run == null) {
                finishExperiment(experimentId, ExperimentStatus.COMPLETED, null, null);
                return;
            }
            PreparedTask task = tasks.prepare(draftOf(experiment), experiment.getOwner(), true);
            TaskSpec spec = tasks.spec(task, run.getCoreTaskId(), experimentId);
            SubmitOptions options = options(experiment, task.settings());
            Instant submittedAt = clock.instant();
            transaction(s -> {
                ExperimentRun r = runs.findById(run.getId()).orElseThrow();
                r.setStatus(RunStatus.QUEUED);
                r.setSubmittedAt(submittedAt);
                runs.save(r);
                Experiment e = experiments.findById(experimentId).orElseThrow();
                if (e.getStatus() == ExperimentStatus.QUEUED) {
                    e.setStatus(ExperimentStatus.RUNNING);
                    e.setStartedAt(submittedAt);
                    experiments.save(e);
                }
            });
            events.publish(experimentId, run.getId(), "RUN_SUBMITTED", Severity.INFO, "Run " + run.getRunIndex()
                    + " of " + experiment.getRepetitions() + " submitted in " + task.mode(),
                    Map.of("runIndex", run.getRunIndex(), "coreTaskId", run.getCoreTaskId(), "mode",
                            task.mode().name()));
            long start = System.nanoTime();
            active.put(experimentId, run.getCoreTaskId());
            TaskHandle handle = coordinator.submit(spec, options,
                    results.sinkFor(experimentId, run.getRunIndex(), run.getCoreTaskId()));
            handle.completion().whenCompleteAsync((outcome, error) -> {
                double wallMs = (System.nanoTime() - start) / 1e6;
                onRunFinished(experimentId, run.getId(), outcome, error, wallMs);
            }, executor);
        } catch (DistriComputeException e) {
            failBeforeStart(experimentId, e.code().name(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Could not start a run of experiment {}", experimentId, e);
            failBeforeStart(experimentId, "SUBMISSION_FAILED", "The run could not be submitted: " + e.getMessage());
        }
    }

    private void onRunFinished(String experimentId, Long runId, TaskOutcome outcome, Throwable error, double wallMs) {
        synchronized (lockFor(experimentId)) {
            onRunFinishedLocked(experimentId, runId, outcome, error, wallMs);
        }
    }

    private void onRunFinishedLocked(String experimentId, Long runId, TaskOutcome outcome, Throwable error,
            double wallMs) {
        active.remove(experimentId);
        try {
            if (error != null || outcome == null) {
                LOG.error("Run {} of {} ended without an outcome", runId, experimentId, error);
                recordFailure(runId, "EXECUTION_FAILED", "The runtime reported no outcome: "
                        + (error == null ? "unknown" : error.getMessage()));
                finishExperiment(experimentId, ExperimentStatus.FAILED, "EXECUTION_FAILED",
                        "A run ended without an outcome");
                return;
            }
            TaskResult result = outcome.result();
            ResultStore.Stored stored = results.take(result.taskId());
            transaction(s -> record(experimentId, runId, outcome, stored, wallMs));
            ExperimentRun run = runs.findById(runId).orElseThrow();
            events.publish(experimentId, runId, "RUN_FINISHED", result.status() == TaskStatus.COMPLETED
                    ? Severity.INFO : Severity.WARN, "Run " + run.getRunIndex() + " " + result.status()
                    + String.format(" in %.3f s", wallMs / 1000), Map.of("runIndex", run.getRunIndex(),
                    "status", result.status().name(), "wallTimeMs", wallMs));
            switch (result.status()) {
                case COMPLETED -> startNextRun(experimentId);
                case CANCELLED -> {
                    cancelRemaining(experimentId, "Cancelled before it started");
                    finishExperiment(experimentId, ExperimentStatus.CANCELLED, "CANCELLED", "Cancelled by the user");
                }
                default -> {
                    cancelRemaining(experimentId, "Not run because run " + run.getRunIndex() + " failed");
                    finishExperiment(experimentId, ExperimentStatus.FAILED,
                            result.error() == null ? "FAILED" : result.error().code().name(),
                            "Run " + run.getRunIndex() + " failed: " + (result.error() == null ? "no details"
                                    : result.error().message()));
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Could not record run {} of {}", runId, experimentId, e);
            finishExperiment(experimentId, ExperimentStatus.FAILED, "RECORDING_FAILED",
                    "The result could not be recorded: " + e.getMessage());
        }
    }

    private void record(String experimentId, Long runId, TaskOutcome outcome, ResultStore.Stored stored,
            double wallMs) {
        TaskResult result = outcome.result();
        Instant now = clock.instant();
        ExperimentRun run = runs.findById(runId).orElseThrow();
        run.setStatus(switch (result.status()) {
            case COMPLETED -> RunStatus.COMPLETED;
            case CANCELLED -> RunStatus.CANCELLED;
            default -> RunStatus.FAILED;
        });
        run.setStartedAt(result.statusHistory().stream().filter(c -> c.status() == TaskStatus.RUNNING)
                .map(StatusChange::at).findFirst().orElse(run.getSubmittedAt()));
        run.setCompletedAt(outcome.snapshot().finishedAt() != null ? outcome.snapshot().finishedAt() : now);
        run.setWallTimeMs(wallMs);
        run.setThreads(result.threads() > 0 ? result.threads() : null);
        run.setPartitions(result.partitions() > 0 ? result.partitions() : outcome.snapshot().totalPartitions());
        if (outcome.snapshot().metrics() != null) {
            run.setWorkersUsed(outcome.snapshot().metrics().workersUsed());
        }
        if (result.result() != null) {
            run.setResultDigest(result.result().digest());
        }
        if (result.verification() != null) {
            run.setVerificationPassed(result.verification().passed());
            run.setVerificationMethod(truncate(result.verification().method(), 500));
        }
        if (result.error() != null) {
            run.setErrorCode(result.error().code().name());
            run.setErrorMessage(truncate(result.error().message(), 2000));
        }
        runs.save(run);

        List<PartitionTask> rows = new ArrayList<>();
        for (PartitionSnapshot p : outcome.snapshot().partitions()) {
            PartitionTask row = new PartitionTask(experimentId, runId, p.index(), p.partitionId());
            row.setStatus(p.status().name());
            row.setAttemptCount(p.attempts().size());
            AttemptSnapshot last = p.attempts().isEmpty() ? null : p.attempts().get(p.attempts().size() - 1);
            AttemptSnapshot delivered = p.attempts().stream().filter(a -> "SUCCEEDED".equals(a.status().name()))
                    .findFirst().orElse(last);
            if (delivered != null) {
                row.setWorkerId(delivered.workerId());
                row.setAssignedAt(delivered.dispatchedAt());
                row.setStartedAt(delivered.acceptedAt());
                row.setCompletedAt(delivered.finishedAt());
                if (delivered.failure() != null && !"SUCCEEDED".equals(delivered.status().name())) {
                    row.setErrorMessage(truncate(delivered.failure(), 2000));
                }
            }
            rows.add(row);
        }
        partitions.saveAll(rows);
        metrics.saveAll(MetricsMapper.map(experimentId, runId, wallMs, result, outcome.snapshot().metrics(), now));

        if (result.result() != null) {
            ExperimentResult record = new ExperimentResult(experimentId, runId);
            record.setResultType(result.result().type());
            record.setSummary(json.write(result.result().values()));
            record.setDigest(result.result().digest());
            record.setValidationStatus(result.verification() == null ? ValidationStatus.NOT_VERIFIED
                    : result.verification().passed() ? ValidationStatus.VERIFIED : ValidationStatus.VERIFICATION_FAILED);
            if (result.verification() != null) {
                record.setValidationMethod(truncate(result.verification().method(), 500));
                record.setValidationMessage(result.verification().message() == null ? null
                        : truncate(result.verification().message(), 2000));
            }
            if (stored != null) {
                record.setStorageRef(stored.ref());
                record.setStorageFormat(stored.format());
                record.setSizeBytes(stored.sizeBytes());
                record.setStoredDigestMatch(stored.digestMatches());
                record.setStorageNote(stored.note() == null ? null : truncate(stored.note(), 500));
            } else {
                record.setStorageNote("No value was delivered for storage");
            }
            record.setCreatedAt(now);
            resultRecords.save(record);
        }
    }

    private void recordFailure(Long runId, String code, String message) {
        transaction(s -> runs.findById(runId).ifPresent(run -> {
            run.setStatus(RunStatus.FAILED);
            run.setErrorCode(code);
            run.setErrorMessage(truncate(message, 2000));
            run.setCompletedAt(clock.instant());
            runs.save(run);
        }));
    }

    private void cancelRemaining(String experimentId, String reason) {
        transaction(s -> {
            for (ExperimentRun run : runs.findByExperimentIdOrderByRunIndexAsc(experimentId)) {
                if (run.getStatus() == RunStatus.PENDING) {
                    run.setStatus(RunStatus.CANCELLED);
                    run.setErrorMessage(reason);
                    runs.save(run);
                }
            }
        });
    }

    private void failBeforeStart(String experimentId, String code, String message) {
        active.remove(experimentId);
        transaction(s -> runs.findByExperimentIdOrderByRunIndexAsc(experimentId).stream()
                .filter(r -> !r.getStatus().isTerminal()).findFirst().ifPresent(run -> {
                    run.setStatus(RunStatus.FAILED);
                    run.setErrorCode(code);
                    run.setErrorMessage(truncate(message, 2000));
                    run.setCompletedAt(clock.instant());
                    runs.save(run);
                }));
        cancelRemaining(experimentId, "Not run because an earlier run could not be submitted");
        finishExperiment(experimentId, ExperimentStatus.FAILED, code, message);
    }

    private void finishExperiment(String experimentId, ExperimentStatus status, String code, String message) {
        cancelRequested.remove(experimentId);
        boolean changed;
        synchronized (lockFor(experimentId)) {
            changed = Boolean.TRUE.equals(transactionResult(s -> {
                Experiment e = experiments.findById(experimentId).orElse(null);
                if (e == null || e.getStatus().isTerminal()) {
                    return false;
                }
                if (status == ExperimentStatus.CANCELLED) {
                    for (ExperimentRun run : runs.findByExperimentIdOrderByRunIndexAsc(experimentId)) {
                        if (run.getStatus() == RunStatus.PENDING) {
                            run.setStatus(RunStatus.CANCELLED);
                            run.setErrorMessage("Cancelled before it started");
                            runs.save(run);
                        }
                    }
                }
                e.finish(status, clock.instant(), code, message);
                experiments.save(e);
                return true;
            }));
        }
        started.remove(experimentId);
        if (changed) {
            events.publish(experimentId, null, "EXPERIMENT_FINISHED", status == ExperimentStatus.COMPLETED
                    ? Severity.INFO : status == ExperimentStatus.CANCELLED ? Severity.WARN : Severity.ERROR,
                    "Experiment " + status.name().toLowerCase() + (message == null ? "" : ": " + message),
                    Map.of("status", status.name()));
        }
        continueBatch(experimentId);
    }

    private Object lockFor(String experimentId) {
        return stripes[Math.floorMod(experimentId.hashCode(), stripes.length)];
    }

    /** Runs a transaction, again (up to three times) if MySQL chose it as the victim of a deadlock. */
    private void transaction(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> work) {
        transactionResult(s -> {
            work.accept(s);
            return null;
        });
    }

    private <T> T transactionResult(org.springframework.transaction.support.TransactionCallback<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(work);
            } catch (org.springframework.dao.PessimisticLockingFailureException e) {
                if (attempt >= 3) {
                    throw e;
                }
                LOG.info("Retrying a transaction after a lock conflict ({})", e.getMostSpecificCause().getMessage());
                try {
                    Thread.sleep(20L * attempt + java.util.concurrent.ThreadLocalRandom.current().nextInt(30));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * Starts the next queued experiment of the finished experiment's batch, unless one of the batch's experiments
     * is still running: a batch runs one experiment at a time, in order ({@code docs/batches-and-templates.md}).
     */
    private synchronized void continueBatch(String experimentId) {
        if (recovering) {
            return;
        }
        try {
            Experiment finished = experiments.findById(experimentId).orElse(null);
            if (finished == null || finished.getBatchId() == null) {
                return;
            }
            List<Experiment> items = experiments.findByBatchIdOrderByBatchPositionAsc(finished.getBatchId());
            if (items.stream().anyMatch(e -> started.contains(e.getId()) || e.getStatus() == ExperimentStatus.RUNNING)) {
                return;
            }
            items.stream().filter(e -> e.getStatus() == ExperimentStatus.QUEUED).findFirst()
                    .ifPresent(next -> start(next.getId()));
        } catch (RuntimeException e) {
            LOG.error("Could not continue the batch of experiment {}", experimentId, e);
        }
    }

    /**
     * Rebuilds the draft of an experiment from what was stored, so each run is validated again by Core.
     *
     * @param experiment the experiment
     * @return the draft
     */
    TaskDraft draftOf(Experiment experiment) {
        Map<String, Object> parameters = new LinkedHashMap<>(json.map(experiment.getParameters()));
        Map<String, String> uploads = new LinkedHashMap<>();
        parameters.entrySet().removeIf(e -> {
            if (e.getValue() instanceof Map<?, ?> ref && ref.get("upload") instanceof String id) {
                uploads.put(e.getKey(), id);
                return true;
            }
            return false;
        });
        ExecutionSettings settings = json.read(experiment.getConfiguration(), ExecutionSettings.class);
        return new TaskDraft(experiment.getAlgorithmId(), parameters, uploads, settings);
    }

    private static SubmitOptions options(Experiment experiment, ExecutionSettings s) {
        SubmitOptions options = SubmitOptions.DEFAULTS;
        if (s.schedulingPolicy() != null) {
            options = options.withPolicy(PolicyName.valueOf(s.schedulingPolicy()));
        }
        if (s.partitionsPerSlot() != null) {
            options = options.withPartitionsPerSlot(s.partitionsPerSlot());
        }
        if (s.maxAttempts() != null) {
            options = options.withMaxAttempts(s.maxAttempts());
        }
        if (s.attemptTimeoutSeconds() != null) {
            options = options.withAttemptTimeout(Duration.ofSeconds(s.attemptTimeoutSeconds()));
        }
        if (s.maxWorkers() != null) {
            options = options.withMaxWorkers(s.maxWorkers());
        }
        if (Boolean.FALSE.equals(experiment.getRecommendationAccepted())) {
            options = options.withOverrideReason(experiment.getOverrideReason() != null ? experiment.getOverrideReason()
                    : "chosen by the user in the application");
        }
        return options;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
