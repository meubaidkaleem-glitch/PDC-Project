package org.districompute.app.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A group of experiments submitted together. */
@Entity
@Table(name = "batches")
public class Batch {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Batch() {
    }

    /**
     * Creates a batch.
     *
     * @param id UUID
     * @param ownerId owner
     * @param name name
     * @param createdAt time
     */
    public Batch(String id, Long ownerId, String name, Instant createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
