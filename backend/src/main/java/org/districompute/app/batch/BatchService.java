package org.districompute.app.batch;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.districompute.app.api.ApiException;
import org.districompute.app.api.ApiException.FieldViolation;
import org.districompute.app.batch.BatchViews.BatchItem;
import org.districompute.app.batch.BatchViews.BatchView;
import org.districompute.app.batch.BatchViews.CreateBatch;
import org.districompute.app.config.AppProperties;
import org.districompute.app.execution.ExecutionService;
import org.districompute.app.experiment.ExperimentService;
import org.districompute.app.experiment.ExperimentViews.CreateExperiment;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.experiment.SubmissionLimiter;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.districompute.app.persistence.Batch;
import org.districompute.app.persistence.BatchRepository;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.security.AuditService;
import org.districompute.app.task.TaskFactory;
import org.districompute.core.DistriComputeException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Batches: several experiments submitted together and run one after another, in order
 * ({@code docs/batches-and-templates.md}). Every item is validated before anything is created; a batch counts as one
 * submission for the rate limit and as one active experiment for the per-user limit.
 */
@Service
public class BatchService {

    private final BatchRepository batches;
    private final ExperimentRepository experiments;
    private final ExperimentRunRepository runs;
    private final AppUserRepository users;
    private final ExperimentService experimentService;
    private final ExecutionService execution;
    private final TaskFactory tasks;
    private final SubmissionLimiter limiter;
    private final AuditService audit;
    private final AppProperties.Limits limits;
    private final Clock clock;

    BatchService(BatchRepository batches, ExperimentRepository experiments, ExperimentRunRepository runs,
            AppUserRepository users, ExperimentService experimentService, ExecutionService execution,
            TaskFactory tasks, SubmissionLimiter limiter, AuditService audit, AppProperties properties, Clock clock) {
        this.batches = batches;
        this.experiments = experiments;
        this.runs = runs;
        this.users = users;
        this.experimentService = experimentService;
        this.execution = execution;
        this.tasks = tasks;
        this.limiter = limiter;
        this.audit = audit;
        this.limits = properties.limits();
        this.clock = clock;
    }

    /**
     * Validates every item, creates the batch and its queued experiments, and starts the first after commit.
     *
     * @param request the batch
     * @param owner the user
     * @param admin whether the user is an administrator
     * @return the batch
     */
    @Transactional
    public BatchView create(CreateBatch request, AppUser owner, boolean admin) {
        if (request.items().size() > limits.maxBatchSize()) {
            throw ApiException.badRequest("BATCH_TOO_LARGE", "A batch holds at most " + limits.maxBatchSize()
                    + " experiments");
        }
        List<FieldViolation> violations = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            String prefix = "items[" + i + "].";
            try {
                tasks.prepare(request.items().get(i).task(), owner, admin);
            } catch (ApiException e) {
                if (e.violations().isEmpty()) {
                    violations.add(new FieldViolation(prefix + "task", e.code(), e.getMessage()));
                }
                e.violations().forEach(v -> violations.add(new FieldViolation(prefix + v.field(), v.code(),
                        v.message())));
            } catch (DistriComputeException e) {
                violations.add(new FieldViolation(prefix + "task", e.code().name(), e.getMessage()));
            }
        }
        if (!violations.isEmpty()) {
            throw ApiException.invalid("Some experiments of the batch are invalid; nothing was submitted",
                    violations);
        }
        experimentService.checkActiveLimit(owner);
        limiter.acquire(owner.getId(), 1);

        Batch batch = batches.save(new Batch(UUID.randomUUID().toString(), owner.getId(), request.name().strip(),
                clock.instant()));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < request.items().size(); i++) {
            BatchItem item = request.items().get(i);
            Experiment experiment = experimentService.createInBatch(new CreateExperiment(item.name(),
                    item.description(), item.task(), item.overrideReason()), owner, admin, batch.getId(), i + 1);
            ids.add(experiment.getId());
        }
        audit.record(owner.getUsername(), "BATCH_CREATED", "BATCH", batch.getId(), ids.size() + " experiments", null);
        String first = ids.get(0);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                execution.start(first);
            }
        });
        return view(batch);
    }

    /**
     * Batches of the user (all batches for administrators), newest first.
     *
     * @param user the user
     * @param admin whether the user is an administrator
     * @param limit how many
     * @return batches
     */
    @Transactional(readOnly = true)
    public List<BatchView> list(AppUser user, boolean admin, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(100, Math.max(1, limit)), Sort.by("createdAt").descending());
        List<Batch> found = admin ? batches.findAll(page).getContent() : batches.findByOwnerId(user.getId(), page);
        return found.stream().map(this::view).toList();
    }

    /**
     * One batch, if the user may see it.
     *
     * @param id batch
     * @param user the user
     * @param admin whether the user is an administrator
     * @return the batch
     */
    @Transactional(readOnly = true)
    public BatchView get(String id, AppUser user, boolean admin) {
        return view(require(id, user, admin));
    }

    /**
     * Cancels the batch's experiments that have not finished: queued ones first, then the running one.
     *
     * @param id batch
     * @param user the user
     * @param admin whether the user is an administrator
     * @return the batch
     */
    public BatchView cancel(String id, AppUser user, boolean admin) {
        Batch batch = require(id, user, admin);
        List<Experiment> items = new ArrayList<>(experiments.findByBatchIdOrderByBatchPositionAsc(id));
        items.sort(Comparator.comparing((Experiment e) -> e.getStatus() == ExperimentStatus.RUNNING));
        int cancelled = 0;
        for (Experiment e : items) {
            if (!e.getStatus().isTerminal() && execution.cancel(e.getId())) {
                cancelled++;
            }
        }
        audit.record(user.getUsername(), "BATCH_CANCELLED", "BATCH", id, cancelled + " experiments", null);
        return view(batch);
    }

    private Batch require(String id, AppUser user, boolean admin) {
        Batch batch = batches.findById(id).orElseThrow(() -> ApiException.notFound("Batch", id));
        if (!admin && !batch.getOwnerId().equals(user.getId())) {
            throw ApiException.notFound("Batch", id);
        }
        return batch;
    }

    private BatchView view(Batch batch) {
        List<Experiment> items = experiments.findByBatchIdOrderByBatchPositionAsc(batch.getId());
        Map<String, List<ExperimentRun>> byExperiment = runs.findByExperimentIdIn(
                items.stream().map(Experiment::getId).toList()).stream()
                .collect(Collectors.groupingBy(ExperimentRun::getExperimentId));
        List<ExperimentSummary> summaries = items.stream()
                .map(e -> experimentService.summary(e, byExperiment.getOrDefault(e.getId(), List.of()))).toList();
        Map<String, Integer> counts = new TreeMap<>();
        items.forEach(e -> counts.merge(e.getStatus().name(), 1, Integer::sum));
        String status;
        if (counts.containsKey("RUNNING")) {
            status = "RUNNING";
        } else if (counts.containsKey("QUEUED")) {
            status = items.stream().anyMatch(e -> e.getStatus().isTerminal()) ? "RUNNING" : "QUEUED";
        } else if (counts.containsKey("FAILED")) {
            status = "FAILED";
        } else if (counts.containsKey("CANCELLED")) {
            status = "CANCELLED";
        } else {
            status = "COMPLETED";
        }
        String owner = users.findById(batch.getOwnerId()).map(AppUser::getUsername).orElse(null);
        return new BatchView(batch.getId(), batch.getName(), owner, batch.getCreatedAt(), status, counts, summaries);
    }
}
