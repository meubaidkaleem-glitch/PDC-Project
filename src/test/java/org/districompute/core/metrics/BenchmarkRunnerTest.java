package org.districompute.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.districompute.core.execution.EngineConfig;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.serialization.SchemaVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Performance-scenario tests. They exercise different sizes, thread counts, partitionings and block sizes, but
 * assert only correctness and report structure, never timing.
 */
@Tag("performance")
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class BenchmarkRunnerTest {

    private static BenchmarkSuite suite(String algorithm, List<Map<String, Object>> sets, List<Integer> threads,
            List<PartitioningOptions> partitionings) {
        return new BenchmarkSuite(SchemaVersion.CURRENT, "test-" + algorithm, "test suite", 1, 3, algorithm, sets,
                threads, partitionings, true);
    }

    @Test
    void measuresEveryConfigurationAgainstTheSequentialBaseline() {
        BenchmarkSuite matmul = suite("matrix.multiply", List.of(
                Map.of("rowsA", 96, "colsA", 64, "colsB", 80, "blockSize", 16),
                Map.of("rowsA", 96, "colsA", 64, "colsB", 80, "blockSize", 64)), List.of(1, 2),
                List.of(PartitioningOptions.ofPartitions(4), PartitioningOptions.ofBlocks(32, 40)));
        List<String> log = new ArrayList<>();
        BenchmarkReport report;
        try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(),
                EngineConfig.defaults().withMaxThreads(2))) {
            report = new BenchmarkRunner(engine).run(matmul, log::add);
        }
        assertEquals(2 * (1 + 2 * 2), report.cases().size());
        assertEquals(report.cases().size(), log.size());
        String digest = report.cases().get(0).resultDigests().get(0);
        for (BenchmarkReport.CaseResult c : report.cases()) {
            assertEquals(3, c.executionNanosSamples().size());
            assertEquals(3, c.execution().count());
            assertTrue(c.execution().min() <= c.execution().median() && c.execution().median() <= c.execution().max());
            assertEquals(List.of(digest), c.resultDigests(), "every run, mode, partitioning and block size agrees");
            assertTrue(c.throughputPerSecond() > 0);
            if (c.mode() == ExecutionMode.SEQUENTIAL_CPU) {
                assertNull(c.speedup());
            } else {
                assertNotNull(c.speedup());
                assertEquals(c.speedup() / c.threads(), c.efficiency(), 1e-12);
                assertTrue(c.baselineLabel().contains("sequential"));
            }
        }
        assertEquals(report, ContractJson.fromJson(ContractJson.toJson(report), BenchmarkReport.class));
        String csv = BenchmarkReports.toCsv(report);
        assertEquals(report.cases().size() + 1, csv.lines().count());
        String markdown = BenchmarkReports.toMarkdown(report);
        assertTrue(markdown.contains("| Case |") && markdown.contains("baseline"));
    }

    @Test
    void coversDifferentSizesAndAlgorithms() {
        try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(),
                EngineConfig.defaults().withMaxThreads(2))) {
            BenchmarkRunner runner = new BenchmarkRunner(engine);
            for (BenchmarkSuite s : List.of(
                    suite("sort.merge", List.of(Map.of("size", 50_000), Map.of("size", 200_000)), List.of(2),
                            List.of(PartitioningOptions.ofPartitions(8))),
                    suite("primes.count", List.of(Map.of("upperBound", 2_000_000)), List.of(2),
                            List.of(PartitioningOptions.ofPartitionSize(300_000))))) {
                BenchmarkReport report = runner.run(s, line -> { });
                assertTrue(report.cases().stream().allMatch(c -> c.resultDigests().size() == 1));
            }
        }
    }

    @Test
    void suiteFilesInTheRepositoryAreValid(@TempDir Path ignored) throws Exception {
        try (var files = Files.list(Path.of("benchmarks/suites"))) {
            List<Path> suites = files.filter(p -> p.toString().endsWith(".json")).toList();
            assertTrue(suites.size() >= 5);
            for (Path file : suites) {
                BenchmarkSuite parsed = ContractJson.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                        BenchmarkSuite.class);
                assertTrue(AlgorithmRegistry.loadDefault().find(
                        org.districompute.core.algorithm.AlgorithmId.of(parsed.algorithm())).isPresent(),
                        file.toString());
            }
        }
    }

    @Test
    void invalidSuitesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkSuite(SchemaVersion.CURRENT, "x", "", 0, 0,
                "sort.merge", List.of(Map.of()), List.of(), List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkSuite(SchemaVersion.CURRENT, "x", "", 0, 1,
                "sort.merge", List.of(), List.of(), List.of(), true));
        assertThrows(IllegalArgumentException.class, () -> new BenchmarkSuite(SchemaVersion.CURRENT, "x", "", 0, 1,
                "sort.merge", List.of(Map.of()), List.of(), List.of(), false));
    }
}
