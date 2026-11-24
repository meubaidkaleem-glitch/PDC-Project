package org.districompute.app.execution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.districompute.app.persistence.Enums.MetricSource;
import org.districompute.app.persistence.RunMetric;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.TaskResult;
import org.districompute.distributed.metrics.DistributedMetrics;

/**
 * Turns the measurements of a finished run into {@link RunMetric} rows ({@code docs/metrics.md}). Names, units and
 * sources are fixed here so that every page, comparison and report uses the same definitions.
 */
public final class MetricsMapper {

    private MetricsMapper() {
    }

    /**
     * The metrics of a run.
     *
     * @param experimentId experiment
     * @param runId run
     * @param wallTimeMs submission to completion, measured by the application
     * @param result Core's result
     * @param distributed the runtime's metrics of a distributed run, or {@code null}
     * @param at recording time
     * @return the rows
     */
    public static List<RunMetric> map(String experimentId, Long runId, double wallTimeMs, TaskResult result,
            DistributedMetrics distributed, Instant at) {
        Rows rows = new Rows(experimentId, runId, at);
        rows.add("wall_time", wallTimeMs, "ms", MetricSource.APPLICATION);

        ExecutionMetrics m = result.metrics();
        if (m != null && m != ExecutionMetrics.NONE) {
            rows.nanos("validation_time", m.validationNanos(), MetricSource.CORE);
            rows.nanos("input_preparation_time", m.inputPreparationNanos(), MetricSource.CORE);
            rows.nanos("partitioning_time", m.partitioningNanos(), MetricSource.CORE);
            rows.nanos("compute_time", m.computeNanos(), MetricSource.CORE);
            rows.nanos("aggregation_time", m.aggregationNanos(), MetricSource.CORE);
            rows.nanos("execution_time", m.executionNanos(), MetricSource.CORE);
            rows.nanos("verification_time", m.verificationNanos(), MetricSource.CORE);
            rows.nanos("busy_time", m.busyNanos(), MetricSource.CORE);
            rows.nanos("scheduling_overhead", m.schedulingOverheadNanos(), MetricSource.CORE);
            rows.add("threads", m.threads(), "count", MetricSource.CORE);
            rows.add("partitions", m.partitions(), "count", MetricSource.CORE);
            if (m.cpuNanos() >= 0) {
                rows.nanos("cpu_time", m.cpuNanos(), MetricSource.CORE);
            }
            if (m.gcCount() >= 0) {
                rows.add("gc_count", m.gcCount(), "count", MetricSource.CORE);
                rows.add("gc_time", m.gcMillis(), "ms", MetricSource.CORE);
            }
            if (m.allocatedBytes() >= 0) {
                rows.add("allocated_memory", m.allocatedBytes(), "bytes", MetricSource.CORE);
            }
        }
        WorkloadProfile profile = result.profile();
        if (profile != null) {
            rows.add("estimated_operations", profile.estimatedOperations(), "operations", MetricSource.CORE);
            rows.add("estimated_peak_memory", profile.estimatedPeakMemoryBytes(), "bytes", MetricSource.CORE);
        }

        if (distributed != null) {
            rows.nanos("runtime_total_time", distributed.totalNanos(), MetricSource.RUNTIME);
            rows.nanos("queue_time", distributed.queueNanos(), MetricSource.RUNTIME);
            rows.nanos("planning_time", distributed.planningNanos(), MetricSource.RUNTIME);
            rows.nanos("distributed_time", distributed.distributedNanos(), MetricSource.RUNTIME);
            rows.nanos("runtime_aggregation_time", distributed.aggregationNanos(), MetricSource.RUNTIME);
            rows.nanos("kernel_time_sum", distributed.kernelNanos(), MetricSource.RUNTIME);
            rows.nanos("worker_execute_time_sum", distributed.workerExecuteNanos(), MetricSource.RUNTIME);
            rows.nanos("transfer_time_sum", distributed.transferNanos(), MetricSource.RUNTIME);
            rows.nanos("serialization_time_sum", distributed.serializationNanos(), MetricSource.RUNTIME);
            rows.nanos("result_processing_time_sum", distributed.resultProcessingNanos(), MetricSource.RUNTIME);
            rows.nanos("scheduling_time", distributed.schedulingNanos(), MetricSource.RUNTIME);
            rows.add("bytes_sent", distributed.bytesSent(), "bytes", MetricSource.RUNTIME);
            rows.add("bytes_received", distributed.bytesReceived(), "bytes", MetricSource.RUNTIME);
            rows.add("attempts", distributed.attempts(), "count", MetricSource.RUNTIME);
            rows.add("failed_attempts", distributed.failedAttempts(), "count", MetricSource.RUNTIME);
            rows.add("retries", distributed.retries(), "count", MetricSource.RUNTIME);
            rows.add("workers_used", distributed.workersUsed(), "count", MetricSource.RUNTIME);
            rows.add("worker_utilization", distributed.workerUtilization(), "ratio", MetricSource.RUNTIME);
            rows.add("throughput_partitions", distributed.throughputPartitionsPerSecond(), "partitions/s",
                    MetricSource.RUNTIME);
            rows.add("load_imbalance", distributed.loadImbalance(), "ratio", MetricSource.RUNTIME);
            double communication = distributed.kernelNanos() + distributed.transferNanos() <= 0 ? 0
                    : (double) distributed.transferNanos() / (distributed.kernelNanos() + distributed.transferNanos());
            rows.add("communication_ratio", communication, "ratio", MetricSource.DERIVED);
        }

        double seconds = m != null && m.executionNanos() > 0 ? m.executionNanos() / 1e9 : wallTimeMs / 1000.0;
        if (profile != null && seconds > 0) {
            rows.add("throughput_operations", profile.estimatedOperations() / seconds, "operations/s",
                    MetricSource.DERIVED);
        }
        if (m != null && m.cpuNanos() > 0 && m.executionNanos() > 0 && distributed == null) {
            rows.add("cpu_utilization", m.cpuNanos() / (double) (m.executionNanos() * Math.max(1, m.threads())),
                    "ratio", MetricSource.DERIVED);
        }
        return rows.list;
    }

    private static final class Rows {
        private final String experimentId;
        private final Long runId;
        private final Instant at;
        private final List<RunMetric> list = new ArrayList<>();

        Rows(String experimentId, Long runId, Instant at) {
            this.experimentId = experimentId;
            this.runId = runId;
            this.at = at;
        }

        void add(String name, double value, String unit, MetricSource source) {
            if (Double.isFinite(value)) {
                list.add(new RunMetric(experimentId, runId, name, value, unit, source, at));
            }
        }

        void nanos(String name, long nanos, MetricSource source) {
            if (nanos >= 0) {
                add(name, nanos / 1e6, "ms", source);
            }
        }
    }
}
