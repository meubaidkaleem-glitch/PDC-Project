package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.districompute.app.persistence.Enums.AdviceBasis;
import org.districompute.app.persistence.Enums.ExperimentStatus;
import org.districompute.app.persistence.Enums.InputSource;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One experiment: an algorithm, its input, an execution configuration and one or more runs (repetitions). Its id is
 * also Core's {@code experimentId}.
 */
@Entity
@Table(name = "experiments")
public class Experiment {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AppUser owner;

    @Column(name = "batch_id", length = 36)
    private String batchId;

    @Column(name = "batch_position")
    private Integer batchPosition;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(name = "algorithm_id", nullable = false, length = 64)
    private String algorithmId;

    @Column(name = "algorithm_version", nullable = false, length = 32)
    private String algorithmVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String parameters;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_source", nullable = false, length = 16)
    private InputSource inputSource;

    @Column(name = "input_ref", length = 255)
    private String inputRef;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_summary")
    private String inputSummary;

    @Column(name = "execution_mode", nullable = false, length = 24)
    private String executionMode;

    @Column(name = "recommended_mode", length = 24)
    private String recommendedMode;

    @Column(name = "recommendation_accepted")
    private Boolean recommendationAccepted;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "advice_basis", length = 24)
    private AdviceBasis adviceBasis;

    @Column(name = "scheduling_policy", length = 24)
    private String schedulingPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String configuration;

    @Column(nullable = false)
    private int repetitions = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExperimentStatus status = ExperimentStatus.QUEUED;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_code", length = 48)
    private String errorCode;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    protected Experiment() {
    }

    /**
     * Creates an experiment.
     *
     * @param id UUID
     * @param owner owner
     * @param createdAt creation time
     */
    public Experiment(String id, AppUser owner, Instant createdAt) {
        this.id = id;
        this.owner = owner;
        this.createdAt = createdAt;
    }

    /**
     * Marks the experiment as ended.
     *
     * @param newStatus terminal status
     * @param at end time
     * @param code error code, or {@code null}
     * @param message error message, or {@code null}
     */
    public void finish(ExperimentStatus newStatus, Instant at, String code, String message) {
        this.status = newStatus;
        this.completedAt = at;
        this.errorCode = code;
        this.errorMessage = message == null ? null : truncate(message, 2000);
    }

    static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AppUser getOwner() {
        return owner;
    }

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public Integer getBatchPosition() {
        return batchPosition;
    }

    public void setBatchPosition(Integer batchPosition) {
        this.batchPosition = batchPosition;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getAlgorithmId() {
        return algorithmId;
    }

    public void setAlgorithmId(String algorithmId) {
        this.algorithmId = algorithmId;
    }

    public String getAlgorithmVersion() {
        return algorithmVersion;
    }

    public void setAlgorithmVersion(String algorithmVersion) {
        this.algorithmVersion = algorithmVersion;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public InputSource getInputSource() {
        return inputSource;
    }

    public void setInputSource(InputSource inputSource) {
        this.inputSource = inputSource;
    }

    public String getInputRef() {
        return inputRef;
    }

    public void setInputRef(String inputRef) {
        this.inputRef = inputRef;
    }

    public String getInputSummary() {
        return inputSummary;
    }

    public void setInputSummary(String inputSummary) {
        this.inputSummary = inputSummary;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getRecommendedMode() {
        return recommendedMode;
    }

    public void setRecommendedMode(String recommendedMode) {
        this.recommendedMode = recommendedMode;
    }

    public Boolean getRecommendationAccepted() {
        return recommendationAccepted;
    }

    public void setRecommendationAccepted(Boolean recommendationAccepted) {
        this.recommendationAccepted = recommendationAccepted;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(String overrideReason) {
        this.overrideReason = overrideReason;
    }

    public AdviceBasis getAdviceBasis() {
        return adviceBasis;
    }

    public void setAdviceBasis(AdviceBasis adviceBasis) {
        this.adviceBasis = adviceBasis;
    }

    public String getSchedulingPolicy() {
        return schedulingPolicy;
    }

    public void setSchedulingPolicy(String schedulingPolicy) {
        this.schedulingPolicy = schedulingPolicy;
    }

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(String configuration) {
        this.configuration = configuration;
    }

    public int getRepetitions() {
        return repetitions;
    }

    public void setRepetitions(int repetitions) {
        this.repetitions = repetitions;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public void setStatus(ExperimentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
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

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
