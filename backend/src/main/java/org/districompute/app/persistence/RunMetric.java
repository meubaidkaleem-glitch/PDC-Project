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
import org.districompute.app.persistence.Enums.MetricSource;

/** A measured or derived value of one run, with its unit and source. */
@Entity
@Table(name = "run_metrics")
public class RunMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "experiment_id", nullable = false, length = 36)
    private String experimentId;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(nullable = false, length = 24)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MetricSource source;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected RunMetric() {
    }

    /**
     * Creates a metric.
     *
     * @param experimentId experiment id
     * @param runId run id
     * @param metricName name
     * @param metricValue value
     * @param unit unit
     * @param source source
     * @param recordedAt time
     */
    public RunMetric(String experimentId, Long runId, String metricName, double metricValue, String unit,
            MetricSource source, Instant recordedAt) {
        this.experimentId = experimentId;
        this.runId = runId;
        this.metricName = metricName;
        this.metricValue = metricValue;
        this.unit = unit;
        this.source = source;
        this.recordedAt = recordedAt;
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

    public String getMetricName() {
        return metricName;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public String getUnit() {
        return unit;
    }

    public MetricSource getSource() {
        return source;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
