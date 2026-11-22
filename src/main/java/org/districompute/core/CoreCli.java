package org.districompute.core;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.execution.CancellationSource;
import org.districompute.core.execution.ExecutionOutcome;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.execution.TaskPreparation;
import org.districompute.core.metrics.BenchmarkCli;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.ResourceSnapshot;
import org.districompute.core.scheduling.StrategyAdvisor;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.task.TaskSpec;

/**
 * Command-line interface for demonstrations and for checking JSON contracts without the other DistriCompute
 * components.
 *
 * <pre>
 *   catalog                      print the algorithm catalog (descriptors and capabilities) as JSON
 *   run &lt;task.json&gt;             execute a task locally and print its TaskResult as JSON
 *   advise &lt;task.json&gt;          print the strategy assessment for a task on this node
 *   benchmark &lt;suite.json&gt; [dir] run a benchmark suite (see BenchmarkCli)
 * </pre>
 * Run it with {@code ./mvnw -q test-compile exec:java -Dexec.args="run examples/tasks/matmul-multithreaded.json"}.
 */
public final class CoreCli {

    private CoreCli() {
    }

    /**
     * Entry point.
     *
     * @param args subcommand and its arguments
     * @throws IOException if a file cannot be read or written
     */
    public static void main(String[] args) throws IOException {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length == 0) {
            out.println("usage: CoreCli catalog | run <task.json> | advise <task.json> | benchmark <suite.json> [dir]");
            return;
        }
        switch (args[0]) {
            case "catalog" -> out.println(ContractJson.toPrettyJson(AlgorithmRegistry.loadDefault().catalog()));
            case "run" -> {
                TaskSpec task = readTask(args);
                try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
                    ExecutionOutcome outcome = engine.execute(task, new CancellationSource(),
                            event -> System.err.printf("[%s] %s %d/%d %s%n", event.taskId(), event.status(),
                                    event.completedUnits(), event.totalUnits(), event.unit()));
                    out.println(ContractJson.toPrettyJson(outcome.taskResult()));
                }
            }
            case "advise" -> {
                TaskSpec task = readTask(args);
                AlgorithmRegistry registry = AlgorithmRegistry.loadDefault();
                ComputeAlgorithm<?, ?> algorithm = registry.resolve(task.algorithm());
                var profile = algorithm.analyze(TaskPreparation.validate(algorithm, task, false));
                out.println(ContractJson.toPrettyJson(new StrategyAdvisor(StrategyAdvisor.Settings.defaults())
                        .recommend(profile, registry.info(algorithm.descriptor().id()).orElseThrow().capabilities(),
                                ResourceSnapshot.localOnly(), List.of(), registry.gpuStatus())));
            }
            case "benchmark" -> BenchmarkCli.main(Arrays.copyOfRange(args, 1, args.length));
            default -> out.println("Unknown command '" + args[0] + "'");
        }
    }

    private static TaskSpec readTask(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(args[0] + " needs a task file");
        }
        return ContractJson.fromJson(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8), TaskSpec.class);
    }
}
