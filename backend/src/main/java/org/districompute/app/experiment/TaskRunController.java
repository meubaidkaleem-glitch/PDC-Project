package org.districompute.app.experiment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.districompute.app.api.ApiException;
import org.districompute.app.execution.ExecutionService;
import org.districompute.app.experiment.ExperimentViews.CreateExperiment;
import org.districompute.app.experiment.ExperimentViews.ExperimentDetail;
import org.districompute.app.experiment.ExperimentViews.LiveRun;
import org.districompute.app.experiment.ExperimentViews.ResultView;
import org.districompute.app.experiment.ExperimentViews.RunView;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExperimentResultRepository;
import org.districompute.app.persistence.ExperimentRun;
import org.districompute.app.persistence.ExperimentRunRepository;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.task.TaskDraft;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task-level API: a task is one run, identified by its Core task id ({@code <experimentId>-r<n>}). Creating a task
 * creates a single-run experiment.
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskRunController {

    private final ExperimentService experiments;
    private final ExperimentAccess access;
    private final ExecutionService execution;
    private final ExperimentRunRepository runs;
    private final ExperimentResultRepository results;
    private final CurrentUser currentUser;

    TaskRunController(ExperimentService experiments, ExperimentAccess access, ExecutionService execution,
            ExperimentRunRepository runs, ExperimentResultRepository results, CurrentUser currentUser) {
        this.experiments = experiments;
        this.access = access;
        this.execution = execution;
        this.runs = runs;
        this.results = results;
        this.currentUser = currentUser;
    }

    /**
     * Submits a task (a single-run experiment).
     *
     * @param request the task
     * @return 202 with the task id and experiment
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody NewTask request) {
        String name = request.name() == null || request.name().isBlank() ? request.task().algorithm() + " task"
                : request.name();
        ExperimentDetail detail = experiments.create(new CreateExperiment(name, null, request.task(),
                request.overrideReason()), currentUser.require(), currentUser.isAdmin(), null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", detail.runs().get(0).coreTaskId(),
                "experimentId", detail.summary().id(), "status", detail.summary().status()));
    }

    /**
     * A task's run record and, while the runtime knows it, its live state.
     *
     * @param taskId Core task id
     * @return the task
     */
    @GetMapping("/{taskId}")
    public Map<String, Object> get(@PathVariable String taskId) {
        ExperimentRun run = run(taskId);
        Experiment experiment = access.require(run.getExperimentId());
        RunView view = ExperimentService.runView(run);
        LiveRun live = experiments.live(experiment, run.getRunIndex());
        return Map.of("experimentId", experiment.getId(), "run", view, "live", live);
    }

    /**
     * Cancels a task's experiment.
     *
     * @param taskId Core task id
     * @return 202
     */
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String taskId) {
        ExperimentRun run = run(taskId);
        Experiment experiment = access.require(run.getExperimentId());
        if (!execution.cancel(experiment.getId())) {
            throw ApiException.conflict("TASK_FINISHED", "The task has already ended");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", taskId, "cancelRequested", true));
    }

    /**
     * A task's result.
     *
     * @param taskId Core task id
     * @return the result
     */
    @GetMapping("/{taskId}/result")
    public ResultView result(@PathVariable String taskId) {
        ExperimentRun run = run(taskId);
        access.require(run.getExperimentId());
        return results.findByRunId(run.getId()).map(r -> experiments.resultView(r, run.getRunIndex()))
                .orElseThrow(() -> ApiException.notFound("Result of task", taskId));
    }

    private ExperimentRun run(String taskId) {
        return runs.findByCoreTaskId(taskId).orElseThrow(() -> ApiException.notFound("Task", taskId));
    }

    /**
     * A task submission.
     *
     * @param name optional name
     * @param task the task
     * @param overrideReason why another mode than the recommendation, if so
     */
    public record NewTask(@Size(max = 200) String name, @NotNull @Valid TaskDraft task,
            @Size(max = 500) String overrideReason) {
    }
}
