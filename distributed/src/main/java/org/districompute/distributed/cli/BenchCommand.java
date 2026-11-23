package org.districompute.distributed.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.districompute.distributed.bench.BenchmarkReport;
import org.districompute.distributed.bench.BenchmarkRunner;
import org.districompute.distributed.bench.BenchmarkSuite;
import org.districompute.distributed.bench.Experiment;

/** The {@code bench} command: runs a benchmark suite on this machine and writes the results. */
public final class BenchCommand {

    /** Options accepted by the command. */
    public static final Set<String> OPTIONS = Set.of("suite", "output", "repeat", "warmup", "worker-heap-mb",
            "only", "run-timeout-s");
    /** Boolean options of the command. */
    public static final Set<String> FLAGS = Set.of();

    private BenchCommand() {
    }

    /**
     * Runs the command.
     *
     * @param options parsed options
     * @param out progress and the location of the results
     * @return 0 if every run completed and was verified, 1 otherwise
     * @throws IOException if worker processes cannot be started or the results cannot be written
     * @throws InterruptedException if interrupted
     */
    public static int run(Options options, PrintStream out) throws IOException, InterruptedException {
        options.requireKnown(OPTIONS);
        // Keep the coordinator's routine log lines out of the progress output (worker processes log to files).
        // Takes effect only if no logger was created before, and never overrides an explicit setting.
        if (System.getProperty("org.slf4j.simpleLogger.defaultLogLevel") == null) {
            System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        }
        String suite = options.get("suite", "standard");
        List<Experiment> experiments = BenchmarkSuite.named(suite);
        if (options.has("only")) {
            Set<String> only = Set.copyOf(Arrays.asList(options.get("only", "").split(",")));
            experiments = experiments.stream().filter(e -> only.contains(e.id())).toList();
            if (experiments.isEmpty()) {
                throw new IllegalArgumentException("--only matches no experiment of suite " + suite);
            }
        }
        BenchmarkRunner.Settings settings = new BenchmarkRunner.Settings(suite, options.getInt("repeat", 5),
                options.getInt("warmup", 1), options.getInt("worker-heap-mb", 1024),
                options.getInt("run-timeout-s", 900));
        Path output = Path.of(options.get("output", "benchmark-results"));
        out.println("Benchmark suite " + suite + ": " + experiments.size() + " experiment(s), " + settings.repeat()
                + " measured run(s) after " + settings.warmup() + " warm-up run(s) per configuration. Results: "
                + output.toAbsolutePath());
        BenchmarkRunner.Results results = BenchmarkRunner.run(experiments, settings, output.resolve("logs"), out);
        BenchmarkReport.write(results, output);
        out.println("Wrote " + output.resolve("results.json") + " and " + output.resolve("summary.md"));
        if (!results.problems().isEmpty()) {
            out.println(results.problems().size() + " run(s) did not complete with a verified result:");
            results.problems().forEach(p -> out.println("  " + p));
            return 1;
        }
        return 0;
    }
}
