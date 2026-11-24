package org.districompute.app.monitoring;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.Enums.Severity;
import org.districompute.app.persistence.ExecutionEvent;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.monitoring.EventBus;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.monitoring.RuntimeEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Subscribes to the embedded coordinator's event bus and turns every runtime event into a stored, streamed
 * application event. Progress events are streamed but not stored (they are frequent and superseded).
 */
@Component
public class RuntimeEventBridge {

    /** Progress events: pushed to live views, never stored. */
    public static final Set<EventType> TRANSIENT = Set.of(EventType.TASK_PROGRESS, EventType.ATTEMPT_PROGRESS);
    private static final Set<EventType> WARNINGS = Set.of(EventType.ATTEMPT_FAILED, EventType.RESULT_IGNORED,
            EventType.PARTITION_RETRIED, EventType.WORKER_DISCONNECTED, EventType.WORKER_REJECTED,
            EventType.EVENTS_DROPPED);

    private final Coordinator coordinator;
    private final EventRecorder recorder;
    private final EventHub hub;
    private final ExperimentDirectory directory;
    private final JsonSupport json;
    private final java.util.List<Consumer<RuntimeEvent>> workerListeners = new java.util.concurrent
            .CopyOnWriteArrayList<>();
    private EventBus.Subscription subscription;

    RuntimeEventBridge(Coordinator coordinator, EventRecorder recorder, EventHub hub, ExperimentDirectory directory,
            JsonSupport json) {
        this.coordinator = coordinator;
        this.recorder = recorder;
        this.hub = hub;
        this.directory = directory;
        this.json = json;
    }

    /**
     * Registers a listener for worker events (worker records are kept in sync this way).
     *
     * @param listener the listener; called on the runtime's delivery thread, so it must be fast
     */
    public void onWorkerEvent(Consumer<RuntimeEvent> listener) {
        workerListeners.add(listener);
    }

    /** Subscribes, including events published while the application was starting. */
    @EventListener(ApplicationReadyEvent.class)
    public void subscribe() {
        if (subscription == null) {
            subscription = coordinator.events().subscribe(this::onEvent, 0);
        }
    }

    void onEvent(RuntimeEvent e) {
        String experimentId = directory.owner(e.experimentId()).isPresent() ? e.experimentId() : null;
        Long ownerId = experimentId == null ? null : directory.owner(experimentId).orElse(null);
        Long runId = directory.run(e.taskId()).orElse(null);
        Map<String, Object> details = e.details();
        Severity severity = severity(e);
        String taskRef = e.attemptId() != null ? e.attemptId() : e.partitionId();
        if (e.type().name().startsWith("WORKER_")) {
            workerListeners.forEach(l -> l.accept(e));
        }
        if (TRANSIENT.contains(e.type())) {
            hub.push(new EventView(null, experimentId, runId, taskRef, e.workerId(), "RUNTIME", e.type().name(),
                    severity.name(), e.message(), details, false, e.timestamp()), ownerId);
            return;
        }
        ExecutionEvent row = new ExecutionEvent(experimentId, runId, taskRef, e.workerId(), e.sequence(),
                e.type().name(), severity, e.message(), json.write(details), false, e.timestamp());
        recorder.enqueue(new EventRecorder.Pending(row, details, ownerId));
    }

    /**
     * The severity the application gives a runtime event.
     *
     * @param e the event
     * @return its severity
     */
    public static Severity severity(RuntimeEvent e) {
        if (e.type() == EventType.TASK_FINISHED) {
            Object status = e.details().get("status");
            return "COMPLETED".equals(status) ? Severity.INFO : "CANCELLED".equals(status) ? Severity.WARN
                    : Severity.ERROR;
        }
        return WARNINGS.contains(e.type()) ? Severity.WARN : Severity.INFO;
    }
}
