package org.districompute.app.experiment;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.districompute.app.api.ApiException;
import org.districompute.app.api.PageResponse;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.execution.ExecutionService;
import org.districompute.app.experiment.ExperimentService.ExperimentFilter;
import org.districompute.app.experiment.ExperimentViews.CreateExperiment;
import org.districompute.app.experiment.ExperimentViews.ExperimentDetail;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.experiment.ExperimentViews.LiveRun;
import org.districompute.app.experiment.ExperimentViews.MetricValue;
import org.districompute.app.monitoring.EventHub;
import org.districompute.app.monitoring.EventView;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.Experiment;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.task.TaskDraft;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Experiments: {@code /api/v1/experiments}. */
@RestController
@RequestMapping("/api/v1/experiments")
public class ExperimentController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "name", "status", "algorithmId",
            "executionMode", "completedAt");

    private final ExperimentService service;
    private final ExperimentAccess access;
    private final ExecutionService execution;
    private final CurrentUser currentUser;
    private final ExecutionEventRepository events;
    private final EventHub hub;
    private final JsonSupport json;

    ExperimentController(ExperimentService service, ExperimentAccess access, ExecutionService execution,
            CurrentUser currentUser, ExecutionEventRepository events, EventHub hub, JsonSupport json) {
        this.service = service;
        this.access = access;
        this.execution = execution;
        this.currentUser = currentUser;
        this.events = events;
        this.hub = hub;
        this.json = json;
    }

    /**
     * Creates and starts an experiment.
     *
     * @param request the experiment
     * @return 202 with the experiment
     */
    @PostMapping
    public ResponseEntity<ExperimentDetail> create(@Valid @RequestBody CreateExperiment request) {
        AppUser user = currentUser.require();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.create(request, user, currentUser.isAdmin(),
                null));
    }

    /**
     * Lists experiments.
     *
     * @param search text in name, id or algorithm
     * @param status status filter
     * @param algorithm algorithm filter
     * @param mode mode filter
     * @param category category filter
     * @param from created at or after
     * @param to created before
     * @param batch batch filter
     * @param mine administrators: only their own
     * @param page page (0-based)
     * @param size page size (at most 100)
     * @param sort {@code field,asc|desc}
     * @return the page
     */
    @GetMapping
    public PageResponse<ExperimentSummary> list(@RequestParam(required = false) String search,
            @RequestParam(required = false) String status, @RequestParam(required = false) String algorithm,
            @RequestParam(required = false) String mode, @RequestParam(required = false) String category,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String batch, @RequestParam(defaultValue = "false") boolean mine,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        if (status != null && !Set.of("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED").contains(status)) {
            throw ApiException.badRequest("INVALID_FILTER", "Unknown status " + status);
        }
        return service.list(new ExperimentFilter(search, status, algorithm, mode, category, from, to, batch, mine),
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)), sort(sort)),
                currentUser.require(), currentUser.isAdmin());
    }

    /**
     * One experiment.
     *
     * @param id experiment id
     * @return the experiment
     */
    @GetMapping("/{id}")
    public ExperimentDetail get(@PathVariable String id) {
        return service.detail(access.require(id));
    }

    /**
     * The task an experiment ran, as it would be submitted again (uploaded inputs by reference), for copying it
     * into a new task, a template or a batch.
     *
     * @param id experiment id
     * @return the task
     */
    @GetMapping("/{id}/task")
    public TaskDraft task(@PathVariable String id) {
        return service.draftOf(access.require(id));
    }

    /**
     * Every metric value of an experiment.
     *
     * @param id experiment id
     * @return values by run
     */
    @GetMapping("/{id}/metrics")
    public List<MetricValue> metrics(@PathVariable String id) {
        return service.metricValues(access.require(id));
    }

    /**
     * The runtime's view of the active or latest run.
     *
     * @param id experiment id
     * @param run run index (optional)
     * @return the live view
     */
    @GetMapping("/{id}/live")
    public LiveRun live(@PathVariable String id, @RequestParam(required = false) Integer run) {
        return service.live(access.require(id), run);
    }

    /**
     * Stored events of an experiment.
     *
     * @param id experiment id
     * @param after only events with a larger id
     * @param limit at most this many (at most 1000)
     * @return events, oldest first
     */
    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "500") int limit) {
        access.require(id);
        return events.findByExperimentIdAndIdGreaterThanOrderByIdAsc(id, after,
                PageRequest.of(0, Math.min(1000, Math.max(1, limit)))).stream()
                .map(e -> EventView.of(e, json.map(e.getDetails()))).toList();
    }

    /**
     * Live events of an experiment (Server-Sent Events). After a reconnection, events missed since
     * {@code Last-Event-ID} are replayed from the database first.
     *
     * @param id experiment id
     * @param lastEventId last event received, if reconnecting
     * @return the stream
     */
    @GetMapping(path = "/{id}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable String id,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        Experiment experiment = access.require(id);
        AppUser user = currentUser.require();
        SseEmitter emitter = hub.subscribe(id, user.getId(), currentUser.isAdmin());
        long after = parse(lastEventId);
        if (after > 0) {
            events.findByExperimentIdAndIdGreaterThanOrderByIdAsc(experiment.getId(), after, PageRequest.of(0, 1000))
                    .forEach(e -> hub.replay(emitter, EventView.of(e, json.map(e.getDetails()))));
        }
        return emitter;
    }

    /**
     * Cancels an experiment.
     *
     * @param id experiment id
     * @return 202, or 409 if it already ended
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id) {
        Experiment experiment = access.require(id);
        if (!execution.cancel(experiment.getId())) {
            throw ApiException.conflict("EXPERIMENT_FINISHED", "The experiment has already ended");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("experimentId", id, "cancelRequested", true));
    }

    /**
     * Runs an experiment again with the same configuration (a new experiment).
     *
     * @param id experiment id
     * @return 202 with the new experiment
     */
    @PostMapping("/{id}/rerun")
    public ResponseEntity<ExperimentDetail> rerun(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.rerun(id, currentUser.require(),
                currentUser.isAdmin(), access));
    }

    /**
     * Deletes a finished experiment.
     *
     * @param id experiment id
     * @param request the request (for the audit record)
     * @return 204
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, HttpServletRequest request) {
        service.delete(access.require(id), currentUser.require().getUsername());
        return ResponseEntity.noContent().build();
    }

    private static long parse(String lastEventId) {
        try {
            return lastEventId == null ? 0 : Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Sort sort(String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            throw ApiException.badRequest("INVALID_SORT", "Sort by one of " + SORTABLE);
        }
        boolean ascending = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        return ascending ? Sort.by(field).ascending() : Sort.by(field).descending();
    }
}
