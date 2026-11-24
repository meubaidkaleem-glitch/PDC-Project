package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** One partition of a run, with the worker and attempts that executed it (the "tasks" entity). */
@Entity
@Table(name = "tasks")
public class PartitionTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false, length = 36)
    private String experimentId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "partition_index", nullable = false)
    private int partitionIndex;

    @Column(name = "partition_id", nullable = false, length = 160)
    private String partitionId;

    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    protected PartitionTask() {
    }

    /**
     * Creates a partition record.
     *
     * @param experimentId experiment id
     * @param runId run id
     * @param partitionIndex index in Core's plan
     * @param partitionId Core partition id
     */
    public PartitionTask(String experimentId, Long runId, int partitionIndex, String partitionId) {
        this.experimentId = experimentId;
        this.runId = runId;
        this.partitionIndex = partitionIndex;
        this.partitionId = partitionId;
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

    public int getPartitionIndex() {
        return partitionIndex;
    }

    public String getPartitionId() {
        return partitionId;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(Instant assignedAt) {
        this.assignedAt = assignedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
