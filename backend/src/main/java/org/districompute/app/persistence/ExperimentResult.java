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
import org.districompute.app.persistence.Enums.StorageFormat;
import org.districompute.app.persistence.Enums.ValidationStatus;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The result of one run: Core's summary and digest, validation, and a reference to the stored value. */
@Entity
@Table(name = "experiment_results")
public class ExperimentResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false, length = 36)
    private String experimentId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "result_type", nullable = false, length = 32)
    private String resultType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column
    private String summary;

    @Column(nullable = false, length = 80)
    private String digest;

    @Enumerated(EnumType.STRING)
    @Column(name = "validation_status", nullable = false, length = 24)
    private ValidationStatus validationStatus;

    @Column(name = "validation_method", length = 500)
    private String validationMethod;

    @Column(name = "validation_message", length = 2000)
    private String validationMessage;

    @Column(name = "storage_ref", length = 255)
    private String storageRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_format", length = 8)
    private StorageFormat storageFormat;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "stored_digest_match")
    private Boolean storedDigestMatch;

    @Column(name = "storage_note", length = 500)
    private String storageNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExperimentResult() {
    }

    /**
     * Creates a result record.
     *
     * @param experimentId experiment id
     * @param runId run id
     */
    public ExperimentResult(String experimentId, Long runId) {
        this.experimentId = experimentId;
        this.runId = runId;
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

    public String getResultType() {
        return resultType;
    }

    public void setResultType(String resultType) {
        this.resultType = resultType;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getDigest() {
        return digest;
    }

    public void setDigest(String digest) {
        this.digest = digest;
    }

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public String getValidationMethod() {
        return validationMethod;
    }

    public void setValidationMethod(String validationMethod) {
        this.validationMethod = validationMethod;
    }

    public String getValidationMessage() {
        return validationMessage;
    }

    public void setValidationMessage(String validationMessage) {
        this.validationMessage = validationMessage;
    }

    public String getStorageRef() {
        return storageRef;
    }

    public void setStorageRef(String storageRef) {
        this.storageRef = storageRef;
    }

    public StorageFormat getStorageFormat() {
        return storageFormat;
    }

    public void setStorageFormat(StorageFormat storageFormat) {
        this.storageFormat = storageFormat;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Boolean getStoredDigestMatch() {
        return storedDigestMatch;
    }

    public void setStoredDigestMatch(Boolean storedDigestMatch) {
        this.storedDigestMatch = storedDigestMatch;
    }

    public String getStorageNote() {
        return storageNote;
    }

    public void setStorageNote(String storageNote) {
        this.storageNote = storageNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
