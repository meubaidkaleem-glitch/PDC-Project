package org.districompute.core.metrics;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.ExecutionOutcome;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;

/**
 * Runs a {@link BenchmarkSuite} on a {@link LocalExecutionEngine}.
 *
 * <p>Methodology (details in {@code docs/performance-methodology.md}):
 * <ul>
 *   <li>Every case first runs {@code warmupRuns} unmeasured times, so the JIT compiles the hot code. Then it runs
 *       {@code measuredRuns} measured times. Cases run one after another, never concurrently.</li>
 *   <li>A run counts only if the task ends {@code COMPLETED}, that is, its result passed independent verification.
 *       A failing run aborts the benchmark: an incorrect configuration has no meaningful performance.</li>
 *   <li>Speedup compares medians of {@code executionNanos} (partitioning + compute + aggregation) against the
 *       sequential baseline of the <em>same parameter set</em>. Input generation and verification are excluded
 *       from both.</li>
 * </ul>
 */
public final class BenchmarkRunner {

    private final LocalExecutionEngine engine;

    /**
     * Creates a runner.
     *
     * @param engine engine to measure; its thread limit must cover every thread count in the suite
     */
    public BenchmarkRunner(LocalExecutionEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Runs a suite.
     *
     * @param suite the suite
     * @param log receives one line per completed case
     * @return the report
     * @throws IllegalStateException if any run fails or is not verified
     */
    public BenchmarkReport run(BenchmarkSuite suite, Consumer<String> log) {
        Instant started = Instant.now();
        List<BenchmarkReport.CaseResult> cases = new ArrayList<>();
        for (int set = 0; set < suite.parameterSets().size(); set++) {
            Map<String, Object> parameters = suite.parameterSets().get(set);
            BenchmarkReport.CaseResult baseline = null;
            if (suite.includeSequentialBaseline()) {
                baseline = measure(suite, set, parameters, ExecutionMode.SEQUENTIAL_CPU, 1, null, null);
                cases.add(baseline);
                log.accept(line(baseline));
            }
            for (int threads : suite.threads()) {
                for (PartitioningOptions partitioning : suite.partitionings()) {
                    BenchmarkReport.CaseResult parallel = measure(suite, set, parameters,
                            ExecutionMode.MULTITHREADED_CPU, threads, partitioning, baseline);
                    cases.add(parallel);
                    log.accept(line(parallel));
                }
            }
        }
        return new BenchmarkReport(SchemaVersion.CURRENT, suite.name(), suite.description(), started, Instant.now(),
                engine.environment(), engine.config().maxThreads(), suite.warmupRuns(), suite.measuredRuns(), cases);
    }

    private BenchmarkReport.CaseResult measure(BenchmarkSuite suite, int set, Map<String, Object> parameters,
            ExecutionMode mode, int threads, PartitioningOptions partitioning, BenchmarkReport.CaseResult baseline) {
        String label = "set " + set + " " + (mode == ExecutionMode.SEQUENTIAL_CPU ? "sequential"
                : "threads=" + threads + " " + describe(partitioning));
        for (int i = 0; i < suite.warmupRuns(); i++) {
            runOnce(suite, parameters, mode, threads, partitioning, label);
        }
        int n = suite.measuredRuns();
        long[] execution = new long[n];
        long[] compute = new long[n];
        long[] partitioningNanos = new long[n];
        long[] aggregation = new long[n];
        long[] overhead = new long[n];
        double[] utilization = new double[n];
        long[] allocated = new long[n];
        boolean cpuAvailable = true;
        boolean allocationAvailable = true;
        long gcCount = 0;
        Set<String> digests = new LinkedHashSet<>();
        TaskResult last = null;
        int processors = engine.environment().availableProcessors();
        for (int i = 0; i < n; i++) {
            TaskResult result = runOnce(suite, parameters, mode, threads, partitioning, label);
            ExecutionMetrics m = result.metrics();
            execution[i] = m.executionNanos();
            compute[i] = m.computeNanos();
            partitioningNanos[i] = m.partitioningNanos();
            aggregation[i] = m.aggregationNanos();
            overhead[i] = m.schedulingOverheadNanos();
            cpuAvailable &= m.cpuNanos() >= 0 && m.executionNanos() > 0;
            utilization[i] = cpuAvailable ? (double) m.cpuNanos() / ((double) m.executionNanos() * processors) : 0;
            allocationAvailable &= m.allocatedBytes() >= 0;
            allocated[i] = m.allocatedBytes();
            gcCount += Math.max(0, m.gcCount());
            digests.add(result.result().digest());
            last = result;
        }
        SampleStatistics executionStats = SampleStatistics.of(execution);
        double operations = last.profile().estimatedOperations();
        double throughput = executionStats.median() > 0 ? operations / (executionStats.median() / 1e9) : 0;
        Double speedup = baseline == null ? null : baseline.execution().median() / executionStats.median();
        List<Long> samples = new ArrayList<>();
        for (long value : execution) {
            samples.add(value);
        }
        return new BenchmarkReport.CaseResult(label, set, last.algorithm(), parameters, mode, threads,
                partitioning == null ? PartitioningOptions.defaults() : partitioning, last.partitions(), operations,
                last.profile().operationUnit(), samples, executionStats, SampleStatistics.of(compute),
                SampleStatistics.of(partitioningNanos), SampleStatistics.of(aggregation),
                SampleStatistics.of(overhead), cpuAvailable ? SampleStatistics.of(utilization) : null,
                allocationAvailable ? SampleStatistics.of(allocated) : null, gcCount, throughput, speedup,
                speedup == null ? null : speedup / threads, baseline == null ? null : baseline.label(),
                new ArrayList<>(digests));
    }

    private TaskResult runOnce(BenchmarkSuite suite, Map<String, Object> parameters, ExecutionMode mode, int threads,
            PartitioningOptions partitioning, String label) {
        TaskSpec.Builder builder = TaskSpec.builder(suite.algorithm()).experimentId(sanitize(suite.name()))
                .parameters(new HashMap<>(parameters)).mode(mode);
        if (mode == ExecutionMode.MULTITHREADED_CPU) {
            builder.maxThreads(threads).partitioning(partitioning);
        }
        ExecutionOutcome outcome = engine.execute(builder.build());
        TaskResult result = outcome.taskResult();
        if (result.status() != TaskStatus.COMPLETED) {
            throw new IllegalStateException("Benchmark case '" + label + "' did not complete: " + result.error());
        }
        return result;
    }

    private static String describe(PartitioningOptions options) {
        if (options == null || PartitioningOptions.defaults().equals(options)) {
            return "default partitions";
        }
        if (options.blockRows() != null) {
            return "blocks " + options.blockRows() + "x" + options.blockCols();
        }
        return options.targetPartitions() != null ? options.targetPartitions() + " partitions"
                : "partition size " + options.partitionSize();
    }

    private static String sanitize(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._:-]", "-");
        return cleaned.length() > 128 ? cleaned.substring(0, 128) : cleaned;
    }

    private static String line(BenchmarkReport.CaseResult result) {
        return String.format("%-48s median %10.3f ms  (min %.3f, max %.3f, sd %.3f)%s", result.label(),
                result.execution().median() / 1e6, result.execution().min() / 1e6, result.execution().max() / 1e6,
                result.execution().standardDeviation() / 1e6,
                result.speedup() == null ? "" : String.format("  speedup %.2f  efficiency %.2f", result.speedup(),
                        result.efficiency()));
    }
}
