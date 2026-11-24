package org.districompute.app.worker;

import jakarta.validation.Valid;
import java.util.List;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.monitoring.EventView;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.districompute.app.persistence.PartitionTaskRepository;
import org.districompute.app.security.CurrentUser;
import org.districompute.app.worker.WorkerViews.ConnectionTest;
import org.districompute.app.worker.WorkerViews.Reason;
import org.districompute.app.worker.WorkerViews.RegisterWorker;
import org.districompute.app.worker.WorkerViews.Registration;
import org.districompute.app.worker.WorkerViews.WorkerView;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Workers: {@code /api/v1/workers}. Reading is open to every user; changes need {@code ADMIN}. */
@RestController
@RequestMapping("/api/v1/workers")
public class WorkerController {

    private final WorkerService service;
    private final CurrentUser currentUser;
    private final ExecutionEventRepository events;
    private final PartitionTaskRepository partitions;
    private final JsonSupport json;

    WorkerController(WorkerService service, CurrentUser currentUser, ExecutionEventRepository events,
            PartitionTaskRepository partitions, JsonSupport json) {
        this.service = service;
        this.currentUser = currentUser;
        this.events = events;
        this.partitions = partitions;
        this.json = json;
    }

    /**
     * Every known worker.
     *
     * @return workers
     */
    @GetMapping
    public List<WorkerView> list() {
        return service.list();
    }

    /**
     * One worker.
     *
     * @param id worker id
     * @return the worker
     */
    @GetMapping("/{id}")
    public WorkerView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Events of a worker, newest first.
     *
     * @param id worker id
     * @param limit at most this many (at most 500)
     * @return events
     */
    @GetMapping("/{id}/events")
    public List<EventView> events(@PathVariable String id, @RequestParam(defaultValue = "100") int limit) {
        service.get(id);
        return events.findByWorkerIdAndSimulatedFalseOrderByIdDesc(id, PageRequest.of(0, Math.min(500, Math.max(1, limit)))).stream()
                .map(e -> EventView.of(e, currentUser.isAdmin() || e.getExperimentId() == null
                        ? json.map(e.getDetails()) : java.util.Map.of())).toList();
    }

    /**
     * Partitions this worker delivered, over all experiments.
     *
     * @param id worker id
     * @return the count
     */
    @GetMapping("/{id}/partitions")
    public java.util.Map<String, Object> partitions(@PathVariable String id) {
        service.get(id);
        return java.util.Map.of("workerId", id, "partitions", partitions.countByWorkerId(id));
    }

    /**
     * Registers (and approves) an expected worker and returns how to start it.
     *
     * @param request the worker
     * @return 201 with the instructions
     */
    @PostMapping("/register")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Registration> register(@Valid @RequestBody RegisterWorker request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(request.workerId(), request.notes(),
                currentUser.require().getUsername()));
    }

    /**
     * How to start a worker with this id.
     *
     * @param id worker id
     * @return the instructions
     */
    @GetMapping("/{id}/registration")
    public Registration registration(@PathVariable String id) {
        return service.registration(id);
    }

    /**
     * Approves a worker.
     *
     * @param id worker id
     * @return the worker
     */
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public WorkerView approve(@PathVariable String id) {
        return service.approve(id, currentUser.require().getUsername());
    }

    /**
     * Deactivates a worker (no new work; running work finishes).
     *
     * @param id worker id
     * @param body reason
     * @return the worker
     */
    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public WorkerView deactivate(@PathVariable String id, @Valid @RequestBody(required = false) Reason body) {
        return service.deactivate(id, body == null ? null : body.reason(), currentUser.require().getUsername());
    }

    /**
     * Activates a deactivated worker.
     *
     * @param id worker id
     * @return the worker
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    public WorkerView activate(@PathVariable String id) {
        return service.activate(id, currentUser.require().getUsername());
    }

    /**
     * Tests a worker's connection.
     *
     * @param id worker id
     * @return the outcome
     */
    @PostMapping("/{id}/test")
    public ConnectionTest test(@PathVariable String id) {
        return service.test(id);
    }

    /**
     * Removes a worker: a connected worker is stopped, then forgotten.
     *
     * @param id worker id
     * @param reason reason
     * @return 204
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> remove(@PathVariable String id, @RequestParam(required = false) String reason) {
        service.remove(id, reason, currentUser.require().getUsername());
        return ResponseEntity.noContent().build();
    }
}
