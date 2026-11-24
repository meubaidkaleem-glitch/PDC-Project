package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A run of a fault scenario in the isolated fault lab ({@code docs/fault-lab.md}). */
@Entity
@Table(name = "fault_lab_runs")
public class FaultLabRun {

    /** Lifecycle of a lab run. */
    public enum Status { RUNNING, COMPLETED, FAILED }

    @Id
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "owner_id")
    private AppUser owner;

    @Column(nullable = false, length = 40)
    private String scenario;

    @Column(nullable = false, length = 16)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Status status = Status.RUNNING;

    @Column(nullable = false)
    private long seed;

    @Column(name = "algorithm_id", nullable = false, length = 100)
    private String algorithmId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "fault_profile", nullable = false)
    private String faultProfile;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String outcome;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected FaultLabRun() {
    }

    /**
     * A new running lab run.
     *
     * @param id identifier
     * @param owner who started it
     * @param scenario scenario id
     * @param seed seed of the fault profile and the generated input
     * @param algorithmId algorithm
     * @param parameters algorithm parameters (JSON)
     * @param faultProfile the faulty worker's profile (JSON)
     * @param createdAt start time
     */
    public FaultLabRun(String id, AppUser owner, String scenario, long seed, String algorithmId, String parameters,
            String faultProfile, Instant createdAt) {
        this.id = id;
        this.owner = owner;
        this.scenario = scenario;
        this.seed = seed;
        this.algorithmId = algorithmId;
        this.parameters = parameters;
        this.faultProfile = faultProfile;
        this.createdAt = createdAt;
    }

    /**
     * Records the end of the run.
     *
     * @param end COMPLETED (the scenario ran; its outcome says whether the task recovered) or FAILED (the lab
     *     itself could not run it)
     * @param outcomeJson outcome (JSON), or {@code null}
     * @param error why the lab failed, or {@code null}
     * @param at end time
     */
    public void finish(Status end, String outcomeJson, String error, Instant at) {
        this.status = end;
        this.outcome = outcomeJson;
        this.errorMessage = error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error;
        this.completedAt = at;
    }

    public String getId() {
        return id;
    }

    public AppUser getOwner() {
        return owner;
    }

    public String getScenario() {
        return scenario;
    }

    public Status getStatus() {
        return status;
    }

    public long getSeed() {
        return seed;
    }

    public String getAlgorithmId() {
        return algorithmId;
    }

    public String getParameters() {
        return parameters;
    }

    public String getFaultProfile() {
        return faultProfile;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
