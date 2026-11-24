package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** An audit record of a security-relevant action. */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String username;

    @Column(nullable = false, length = 48)
    private String action;

    @Column(name = "target_type", length = 32)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(length = 2000)
    private String details;

    @Column(name = "remote_address", length = 64)
    private String remoteAddress;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    /**
     * Creates an audit record.
     *
     * @param username acting user, or {@code null}
     * @param action action
     * @param targetType target type, or {@code null}
     * @param targetId target id, or {@code null}
     * @param details details, or {@code null} (truncated to 2000 characters)
     * @param remoteAddress client address, or {@code null}
     * @param occurredAt time
     */
    public AuditEvent(String username, String action, String targetType, String targetId, String details,
            String remoteAddress, Instant occurredAt) {
        this.username = username;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.details = details == null ? null : Experiment.truncate(details, 2000);
        this.remoteAddress = remoteAddress;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetails() {
        return details;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
