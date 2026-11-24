package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A reusable experiment configuration. */
@Entity
@Table(name = "experiment_templates")
public class ExperimentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "algorithm_id", nullable = false, length = 64)
    private String algorithmId;

    @Column(name = "execution_mode", nullable = false, length = 24)
    private String executionMode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String configuration;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExperimentTemplate() {
    }

    /**
     * Creates a template.
     *
     * @param ownerId owner
     * @param createdAt time
     */
    public ExperimentTemplate(Long ownerId, Instant createdAt) {
        this.ownerId = ownerId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
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

    public String getAlgorithmId() {
        return algorithmId;
    }

    public void setAlgorithmId(String algorithmId) {
        this.algorithmId = algorithmId;
    }

    public String getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(String executionMode) {
        this.executionMode = executionMode;
    }

    public String getParameters() {
        return parameters;
    }

    public void setParameters(String parameters) {
        this.parameters = parameters;
    }

    public String getConfiguration() {
        return configuration;
    }

    public void setConfiguration(String configuration) {
        this.configuration = configuration;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
