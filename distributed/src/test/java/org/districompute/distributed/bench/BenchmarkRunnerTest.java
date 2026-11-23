package org.districompute.distributed.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.distributed.Json;
import org.districompute.distributed.bench.BenchmarkRunner.Results;
import org.districompute.distributed.bench.BenchmarkRunner.Sample;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/**
 * Runs the quick suite with real worker processes. It checks that every run completes, is verified and agrees with
 * the other runs of its workload, and that the reports are written. It asserts nothing about speed.
 */
@Tag("performance")
class BenchmarkRunnerTest {

    @Test
    void theQuickSuiteRunsOnRealWorkerProcessesAndEveryResultIsVerified(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream progress = new ByteArrayOutputStream();
        Results results = BenchmarkRunner.run(BenchmarkSuite.quick(),
                new BenchmarkRunner.Settings("quick", 2, 1, 256, 120), dir.resolve("logs"),
                new PrintStream(progress, true, StandardCharsets.UTF_8));
        assertTrue(results.problems().isEmpty(), results.problems() + "\n" + progress);
        long configurations = BenchmarkSuite.quick().stream().mapToLong(e -> e.configurations().size()).sum();
        assertEquals(configurations * 3, results.samples().size());
        for (Sample sample : results.samples()) {
            assertTrue(sample.warmup() || sample.counts(), sample.toString());
            assertTrue(sample.wallNanos() > 0);
            if (sample.mode() == ExecutionMode.DISTRIBUTED_CPU) {
                assertNotNull(sample.distributed(), sample.toString());
                assertEquals(sample.distributed().partitions(), sample.distributed().succeededAttempts());
                assertTrue(sample.distributed().bytesSent() > 0 && sample.distributed().kernelNanos() > 0);
            }
        }
        // The slow worker took part, and its fault profile was explicitly enabled.
        assertTrue(results.samples().stream().filter(s -> s.distributed() != null).flatMap(s -> s.distributed()
                .perWorker().stream()).anyMatch(w -> w.workerId().equals("slow1") && w.succeeded() > 0));

        BenchmarkReport.write(results, dir);
        JsonNode json = Json.readTree(Files.readAllBytes(dir.resolve("results.json")));
        assertEquals(results.samples().size(), json.path("samples").size());
        assertEquals(Runtime.getRuntime().availableProcessors(),
                json.path("environment").path("availableProcessors").asInt());
        String summary = Files.readString(dir.resolve("summary.md"), StandardCharsets.UTF_8);
        assertTrue(summary.contains("| distributed 2w x 1s | 2 |"), summary);
        assertTrue(!summary.contains("no verified run") && !summary.contains("## Problems"), summary);
    }
}
