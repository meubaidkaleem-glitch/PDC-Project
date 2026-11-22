package org.districompute.core.metrics;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.districompute.core.execution.EngineConfig;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.serialization.ContractJson;

/**
 * Command-line entry point for benchmarks.
 *
 * <pre>
 *   ./mvnw -q test-compile exec:java -Dexec.args="benchmarks/suites/matmul-scaling.json target/benchmarks"
 * </pre>
 * Writes {@code <suite>.json}, {@code <suite>.csv} and {@code <suite>.md} to the output directory. Close other
 * workloads first, because the numbers describe this machine at this moment.
 */
public final class BenchmarkCli {

    private BenchmarkCli() {
    }

    /**
     * Runs a suite file.
     *
     * @param args suite file, then an optional output directory (default {@code target/benchmarks})
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length < 1) {
            out.println("usage: BenchmarkCli <suite.json> [output-directory]");
            return;
        }
        Path suiteFile = Path.of(args[0]);
        Path outputDirectory = Path.of(args.length > 1 ? args[1] : "target/benchmarks");
        BenchmarkSuite suite = ContractJson.fromJson(Files.readString(suiteFile, StandardCharsets.UTF_8),
                BenchmarkSuite.class);
        int engineThreads = Math.max(Runtime.getRuntime().availableProcessors(),
                suite.threads().stream().mapToInt(Integer::intValue).max().orElse(1));
        EngineConfig config = EngineConfig.defaults().withMaxThreads(engineThreads);
        BenchmarkReport report;
        try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(), config)) {
            out.println("Running suite '" + suite.name() + "' on " + engine.environment().cpuModel() + " ("
                    + engine.environment().availableProcessors() + " processors)");
            report = new BenchmarkRunner(engine).run(suite, out::println);
        }
        Files.createDirectories(outputDirectory);
        Files.writeString(outputDirectory.resolve(suite.name() + ".json"), ContractJson.toPrettyJson(report),
                StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve(suite.name() + ".csv"), BenchmarkReports.toCsv(report),
                StandardCharsets.UTF_8);
        Files.writeString(outputDirectory.resolve(suite.name() + ".md"), BenchmarkReports.toMarkdown(report),
                StandardCharsets.UTF_8);
        out.println("Wrote " + outputDirectory.toAbsolutePath().resolve(suite.name() + ".{json,csv,md}"));
    }
}
