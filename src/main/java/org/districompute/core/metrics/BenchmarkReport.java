package org.districompute.core.metrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Result of running a {@link BenchmarkSuite}: raw samples, statistics and derived metrics for every case, plus the
 * environment they were measured in. Only runs that completed with a passed verification are included.
 *
 * @param schemaVersion contract schema version
 * @param suite suite name
 * @param description suite description
 * @param startedAt start of the run
 * @param finishedAt end of the run
 * @param environment hardware and software environment
 * @param engineThreads worker threads of the engine used
 * @param warmupRuns unmeasured runs per case
 * @param measuredRuns measured runs per case
 * @param cases one entry per measured configuration
 */
public record BenchmarkReport(
        String schemaVersion,
        String suite,
        String description,
        Instant startedAt,
        Instant finishedAt,
        EnvironmentInfo environment,
        int engineThreads,
        int warmupRuns,
        int measuredRuns,
        List<CaseResult> cases) {

    /** Validates the components. */
    public BenchmarkReport {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(environment, "environment");
        cases = List.copyOf(cases);
    }

    /**
     * Measurements of one configuration. Durations are nanoseconds. Statistics are over the measured runs.
     *
     * @param label human-readable case label
     * @param parameterSet index of the parameter set in the suite
     * @param algorithm algorithm and exact version
     * @param parameters parameters used
     * @param mode execution mode
     * @param threads threads used
     * @param partitioning requested partitioning
     * @param partitions partitions actually executed
     * @param estimatedOperations estimated operation count from the workload profile
     * @param operationUnit unit of {@code estimatedOperations}
     * @param executionNanosSamples raw {@code executionNanos} of every measured run
     * @param execution statistics of {@code executionNanos} (partitioning + compute + aggregation)
     * @param compute statistics of {@code computeNanos}
     * @param partitioningTime statistics of {@code partitioningNanos}
     * @param aggregation statistics of {@code aggregationNanos}
     * @param schedulingOverhead statistics of {@code schedulingOverheadNanos}
     * @param cpuUtilization statistics of {@code cpuNanos / (executionNanos × availableProcessors)}, or {@code null}
     *     if CPU time is unavailable
     * @param allocatedBytes statistics of heap bytes allocated per run, or {@code null} if unavailable
     * @param gcCount garbage collections summed over the measured runs
     * @param throughputPerSecond {@code estimatedOperations / median(executionNanos)} in operations per second
     * @param speedup {@code median(baseline executionNanos) / median(executionNanos)}, or {@code null} without a
     *     baseline
     * @param efficiency {@code speedup / threads}, or {@code null} without a baseline
     * @param baselineLabel label of the sequential baseline case, or {@code null}
     * @param resultDigests distinct result digests over all measured runs (one for deterministic algorithms)
     */
    public record CaseResult(
            String label,
            int parameterSet,
            AlgorithmRef algorithm,
            Map<String, Object> parameters,
            ExecutionMode mode,
            int threads,
            PartitioningOptions partitioning,
            int partitions,
            double estimatedOperations,
            String operationUnit,
            List<Long> executionNanosSamples,
            SampleStatistics execution,
            SampleStatistics compute,
            SampleStatistics partitioningTime,
            SampleStatistics aggregation,
            SampleStatistics schedulingOverhead,
            SampleStatistics cpuUtilization,
            SampleStatistics allocatedBytes,
            long gcCount,
            double throughputPerSecond,
            Double speedup,
            Double efficiency,
            String baselineLabel,
            List<String> resultDigests) {

        /** Copies the collections. */
        public CaseResult {
            parameters = ParameterValues.normalizeAll(parameters);
            executionNanosSamples = List.copyOf(executionNanosSamples);
            resultDigests = List.copyOf(resultDigests);
        }
    }
}
