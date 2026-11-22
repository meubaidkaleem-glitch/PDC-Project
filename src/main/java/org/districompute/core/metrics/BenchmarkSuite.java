package org.districompute.core.metrics;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Definition of a benchmark: one algorithm, several parameter sets (for example problem sizes or block sizes), and
 * the multithreaded configurations to compare against the sequential baseline. Suites are stored as JSON in
 * {@code benchmarks/}.
 *
 * <p>For each parameter set the runner executes the sequential baseline (when {@code includeSequentialBaseline} is
 * set) and then every combination of {@code threads} × {@code partitionings} in {@code MULTITHREADED_CPU} mode.
 *
 * @param schemaVersion contract schema version
 * @param name suite name, used in report file names
 * @param description what the suite investigates
 * @param warmupRuns unmeasured runs per case, to let the JIT compile the hot code
 * @param measuredRuns measured runs per case, at least 1
 * @param algorithm algorithm identifier
 * @param parameterSets parameter sets to measure
 * @param threads thread counts for multithreaded cases (empty for sequential only)
 * @param partitionings partitioning options for multithreaded cases (empty means the algorithm's default)
 * @param includeSequentialBaseline whether to measure the sequential baseline for every parameter set
 */
public record BenchmarkSuite(
        String schemaVersion,
        String name,
        String description,
        int warmupRuns,
        int measuredRuns,
        String algorithm,
        List<Map<String, Object>> parameterSets,
        List<Integer> threads,
        List<PartitioningOptions> partitionings,
        boolean includeSequentialBaseline) {

    /** Validates and normalizes the suite. */
    public BenchmarkSuite {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(name, "name");
        description = description == null ? "" : description;
        if (warmupRuns < 0 || measuredRuns < 1) {
            throw new IllegalArgumentException("warmupRuns must be >= 0 and measuredRuns >= 1");
        }
        Objects.requireNonNull(algorithm, "algorithm");
        if (parameterSets == null || parameterSets.isEmpty()) {
            throw new IllegalArgumentException("At least one parameter set is required");
        }
        parameterSets = parameterSets.stream().map(ParameterValues::normalizeAll).toList();
        threads = threads == null ? List.of() : List.copyOf(threads);
        if (threads.stream().anyMatch(t -> t < 1)) {
            throw new IllegalArgumentException("Thread counts must be at least 1");
        }
        partitionings = partitionings == null || partitionings.isEmpty()
                ? List.of(PartitioningOptions.defaults()) : List.copyOf(partitionings);
        if (!includeSequentialBaseline && threads.isEmpty()) {
            throw new IllegalArgumentException("A suite needs the sequential baseline or at least one thread count");
        }
    }
}
