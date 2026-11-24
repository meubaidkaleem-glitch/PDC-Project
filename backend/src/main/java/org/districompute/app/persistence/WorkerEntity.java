package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The application's record of a worker: last known state, approval and history counters. */
@Entity
@Table(name = "workers")
public class WorkerEntity {

    @Id
    @Column(name = "worker_id", length = 64)
    private String workerId;

    @Column(length = 255)
    private String hostname;

    @Column(length = 255)
    private String endpoint;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String capabilities;

    @Column(name = "connection_status", nullable = false, length = 16)
    private String connectionStatus;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean approved;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(nullable = false)
    private boolean deactivated;

    @Column(length = 500)
    private String notes;

    @Column(name = "completed_tasks", nullable = false)
    private long completedTasks;

    @Column(name = "failed_tasks", nullable = false)
    private long failedTasks;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WorkerEntity() {
    }

    /**
     * Creates a worker record.
     *
     * @param workerId worker id
     * @param connectionStatus initial status
     * @param now creation time
     */
    public WorkerEntity(String workerId, String connectionStatus, Instant now) {
        this.workerId = workerId;
        this.connectionStatus = connectionStatus;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getWorkerId() {
        return workerId;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(String capabilities) {
        this.capabilities = capabilities;
    }

    public String getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(String connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }

    public void setLastHeartbeatAt(Instant lastHeartbeatAt) {
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public boolean isDeactivated() {
        return deactivated;
    }

    public void setDeactivated(boolean deactivated) {
        this.deactivated = deactivated;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public long getCompletedTasks() {
        return completedTasks;
    }

    public void setCompletedTasks(long completedTasks) {
        this.completedTasks = completedTasks;
    }

    public long getFailedTasks() {
        return failedTasks;
    }

    public void setFailedTasks(long failedTasks) {
        this.failedTasks = failedTasks;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
