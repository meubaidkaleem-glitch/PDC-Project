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
import org.districompute.app.persistence.Enums.RunStatus;

/** One run (repetition) of an experiment: one Core task, identified by {@code coreTaskId}. */
@Entity
@Table(name = "experiment_runs")
public class ExperimentRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false, length = 36)
    private String experimentId;

    @Column(name = "run_index", nullable = false)
    private int runIndex;

    @Column(name = "core_task_id", nullable = false, length = 128)
    private String coreTaskId;

    @Column(name = "execution_mode", nullable = false, length = 24)
    private String executionMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunStatus status = RunStatus.PENDING;

    @Column
    private Integer threads;

    @Column
    private Integer partitions;

    @Column(name = "workers_used")
    private Integer workersUsed;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "wall_time_ms")
    private Double wallTimeMs;

    @Column(name = "result_digest", length = 80)
    private String resultDigest;

    @Column(name = "verification_passed")
    private Boolean verificationPassed;

    @Column(name = "verification_method", length = 500)
    private String verificationMethod;

    @Column(name = "error_code", length = 48)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    protected ExperimentRun() {
    }

    /**
     * Creates a pending run.
     *
     * @param experimentId experiment id
     * @param runIndex 1-based index
     * @param executionMode Core execution mode name
     */
    public ExperimentRun(String experimentId, int runIndex, String executionMode) {
        this.experimentId = experimentId;
        this.runIndex = runIndex;
        this.coreTaskId = coreTaskId(experimentId, runIndex);
        this.executionMode = executionMode;
    }

    /**
     * Core task id of a run.
     *
     * @param experimentId experiment id
     * @param runIndex 1-based index
     * @return {@code <experimentId>-r<runIndex>}
     */
    public static String coreTaskId(String experimentId, int runIndex) {
        return experimentId + "-r" + runIndex;
    }

    public Long getId() {
        return id;
    }

    public String getExperimentId() {
        return experimentId;
    }

    public int getRunIndex() {
        return runIndex;
    }

    public String getCoreTaskId() {
        return coreTaskId;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public RunStatus getStatus() {
        return status;
    }

    public void setStatus(RunStatus status) {
        this.status = status;
    }

    public Integer getThreads() {
        return threads;
    }

    public void setThreads(Integer threads) {
        this.threads = threads;
    }

    public Integer getPartitions() {
        return partitions;
    }

    public void setPartitions(Integer partitions) {
        this.partitions = partitions;
    }

    public Integer getWorkersUsed() {
        return workersUsed;
    }

    public void setWorkersUsed(Integer workersUsed) {
        this.workersUsed = workersUsed;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Instant submittedAt) {
        this.submittedAt = submittedAt;
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

    public Double getWallTimeMs() {
        return wallTimeMs;
    }

    public void setWallTimeMs(Double wallTimeMs) {
        this.wallTimeMs = wallTimeMs;
    }

    public String getResultDigest() {
        return resultDigest;
    }

    public void setResultDigest(String resultDigest) {
        this.resultDigest = resultDigest;
    }

    public Boolean getVerificationPassed() {
        return verificationPassed;
    }

    public void setVerificationPassed(Boolean verificationPassed) {
        this.verificationPassed = verificationPassed;
    }

    public String getVerificationMethod() {
        return verificationMethod;
    }

    public void setVerificationMethod(String verificationMethod) {
        this.verificationMethod = verificationMethod;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
