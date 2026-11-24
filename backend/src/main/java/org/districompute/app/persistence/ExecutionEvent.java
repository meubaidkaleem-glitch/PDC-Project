package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.districompute.app.persistence.Enums.Severity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A persisted runtime or application event, for history, monitoring replay and reports. */
@Entity
@Table(name = "execution_events")
public class ExecutionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", length = 36)
    private String experimentId;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "task_ref", length = 160)
    private String taskRef;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(name = "runtime_sequence")
    private Long runtimeSequence;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Severity severity;

    @Column(nullable = false, length = 1000)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String details;

    @Column(nullable = false)
    private boolean simulated;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ExecutionEvent() {
    }

    /**
     * Creates an event.
     *
     * @param experimentId experiment, or {@code null}
     * @param runId run, or {@code null}
     * @param taskRef partition or attempt id, or {@code null}
     * @param workerId worker, or {@code null}
     * @param runtimeSequence Distributed event sequence, or {@code null} for application events
     * @param eventType type
     * @param severity severity
     * @param message message (truncated to 1000 characters)
     * @param details JSON details, or {@code null}
     * @param simulated whether the event comes from the fault lab
     * @param occurredAt time
     */
    public ExecutionEvent(String experimentId, Long runId, String taskRef, String workerId, Long runtimeSequence,
            String eventType, Severity severity, String message, String details, boolean simulated,
            Instant occurredAt) {
        this.experimentId = experimentId;
        this.runId = runId;
        this.taskRef = taskRef;
        this.workerId = workerId;
        this.runtimeSequence = runtimeSequence;
        this.eventType = eventType;
        this.severity = severity;
        this.message = Experiment.truncate(message, 1000);
        this.details = details;
        this.simulated = simulated;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public Long getRunId() {
        return runId;
    }

    public String getTaskRef() {
        return taskRef;
    }

    public String getWorkerId() {
        return workerId;
    }

    public Long getRuntimeSequence() {
        return runtimeSequence;
    }

    public String getEventType() {
        return eventType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getDetails() {
        return details;
    }

    public boolean isSimulated() {
        return simulated;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
