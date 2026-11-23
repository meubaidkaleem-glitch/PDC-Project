package org.districompute.distributed.bench;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.districompute.core.CoreVersion;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.result.TaskResult;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.task.TaskSpec;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.coordinator.TaskHandle;
import org.districompute.distributed.metrics.DistributedMetrics;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;

/**
 * Runs experiments and collects one {@link Sample} per run.
 *
 * <p>Every configuration gets a fresh coordinator in this JVM and fresh worker processes. Local runs
 * ({@code SEQUENTIAL_CPU}, {@code MULTITHREADED_CPU}) execute through Core's {@code LocalExecutionEngine} in this JVM
 * while no worker process is running. Each configuration starts with unmeasured warm-up runs (JIT compilation,
 * and history for the performance-aware policy). The wall time of a run is measured here, from just before
 * {@code submit} to the completion of the task, as the Application would see it.
 *
 * <p>A run counts only if Core reports {@code COMPLETED}, its verification passed, and its digest equals the
 * digest of every other run of the same workload. Anything else is recorded as a problem.
 */
public final class BenchmarkRunner {

    private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(60);
    private static final SecureRandom RANDOM = new SecureRandom();

    private BenchmarkRunner() {
    }

    /**
     * Benchmark settings.
     *
     * @param suite suite name, for the report
     * @param repeat measured runs per configuration
     * @param warmup unmeasured runs per configuration before the measured ones
     * @param workerHeapMegabytes maximum heap of each worker process
     * @param runTimeoutSeconds limit for one run
     */
    public record Settings(String suite, int repeat, int warmup, int workerHeapMegabytes, long runTimeoutSeconds) {

        /** Validates the settings. */
        public Settings {
            Objects.requireNonNull(suite, "suite");
            if (repeat < 1 || warmup < 0 || workerHeapMegabytes < 64 || runTimeoutSeconds < 1) {
                throw new IllegalArgumentException("repeat >= 1, warmup >= 0, worker heap >= 64 MiB and a positive "
                        + "run timeout are required");
            }
        }
    }

    /**
     * One run.
     *
     * @param experiment experiment id
     * @param configuration configuration label
     * @param workload workload name
     * @param mode execution mode
     * @param cores cores the configuration may use
     * @param run run number, starting at 1 for the first warm-up run
     * @param warmup {@code true} for an unmeasured warm-up run
     * @param taskId task id
     * @param status Core task status
     * @param verified {@code true} if Core's verification passed
     * @param digest result digest
     * @param wallNanos submission to completion, measured by the benchmark
     * @param partitions partitions Core planned
     * @param distributed runtime metrics of a distributed run, otherwise {@code null}
     * @param core Core's metrics from the result
     * @param error error message of a failed run, otherwise {@code null}
     */
    public record Sample(String experiment, String configuration, String workload, ExecutionMode mode, int cores,
            int run, boolean warmup, String taskId, String status, boolean verified, String digest, long wallNanos,
            int partitions, DistributedMetrics distributed, ExecutionMetrics core, String error) {

        /**
         * Whether the run is a measured, completed and verified run.
         *
         * @return {@code true} if the run counts
         */
        public boolean counts() {
            return !warmup && "COMPLETED".equals(status) && verified && error == null;
        }
    }

    /**
     * Everything a benchmark produced.
     *
     * @param environment machine, JVM and software versions
     * @param settings the settings
     * @param experiments the experiments that ran
     * @param samples one per run, in order
     * @param problems runs that failed, were not verified or disagreed with other runs; empty if all is well
     */
    public record Results(Map<String, Object> environment, Settings settings, List<Experiment> experiments,
            List<Sample> samples, List<String> problems) {
    }

    /**
     * Runs the experiments.
     *
     * @param experiments the experiments
     * @param settings the settings
     * @param logDirectory where worker logs and generated fault profiles go
     * @param progress progress lines
     * @return the results
     * @throws IOException if worker processes cannot be started or do not register
     * @throws InterruptedException if interrupted
     */
    public static Results run(List<Experiment> experiments, Settings settings, Path logDirectory, PrintStream progress)
            throws IOException, InterruptedException {
        Files.createDirectories(logDirectory);
        Map<String, Object> environment = environment(settings);
        List<Sample> samples = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Map<String, String> digests = new HashMap<>();
        for (Experiment experiment : experiments) {
            for (Configuration configuration : experiment.configurations()) {
                runConfiguration(experiment, configuration, settings, logDirectory, progress, samples, problems,
                        digests);
            }
        }
        return new Results(environment, settings, experiments, List.copyOf(samples), List.copyOf(problems));
    }

    private static void runConfiguration(Experiment experiment, Configuration c, Settings settings, Path logs,
            PrintStream progress, List<Sample> samples, List<String> problems, Map<String, String> digests)
            throws IOException, InterruptedException {
        String secret = newSecret();
        CoordinatorConfig config = CoordinatorConfig.builder(SharedSecret.of(secret)).bindAddress("127.0.0.1").port(0)
                .allowFaultSimulationWorkers(c.slowWorkers() > 0).build();
        String prefix = (experiment.id() + "-" + c.label()).replaceAll("[^A-Za-z0-9.-]+", "_");
        try (Coordinator coordinator = Coordinator.start(config);
                WorkerProcesses workers = new WorkerProcesses(logs, settings.workerHeapMegabytes(), secret)) {
            if (c.mode() == ExecutionMode.DISTRIBUTED_CPU) {
                for (int i = 1; i <= c.workers(); i++) {
                    workers.start("w" + i, coordinator.port(), c.slotsPerWorker(), 1, prefix);
                }
                for (int i = 1; i <= c.slowWorkers(); i++) {
                    workers.start("slow" + i, coordinator.port(), c.slotsPerWorker(), c.slowdown(), prefix);
                }
                workers.awaitRegistered(coordinator, REGISTRATION_TIMEOUT);
            }
            int runs = settings.warmup() + settings.repeat();
            for (int run = 1; run <= runs; run++) {
                boolean warmup = run <= settings.warmup();
                Sample sample = runOnce(coordinator, experiment, c, run, warmup, prefix, settings);
                samples.add(sample);
                String problem = check(sample, digests);
                if (problem != null) {
                    problems.add(problem);
                }
                progress.printf(Locale.ROOT, "[%s] %-40s %s %d/%d: %8.3f s  %s%s%n", experiment.id(), c.label(),
                        warmup ? "warm-up" : "run", warmup ? run : run - settings.warmup(),
                        warmup ? settings.warmup() : settings.repeat(), sample.wallNanos() / 1e9, sample.status(),
                        problem == null ? "" : "  PROBLEM: " + problem);
                if (sample.error() != null && sample.error().startsWith("timed out")) {
                    break;
                }
            }
        }
    }

    private static Sample runOnce(Coordinator coordinator, Experiment experiment, Configuration c, int run,
            boolean warmup, String prefix, Settings settings) throws InterruptedException {
        String taskId = (prefix + "-r" + run).replaceAll("[^A-Za-z0-9._-]", "_");
        if (taskId.length() > 120) {
            taskId = taskId.substring(taskId.length() - 120);
        }
        TaskSpec spec = c.workload().spec(taskId, c.mode(), c.threads());
        SubmitOptions options = SubmitOptions.DEFAULTS.withOverrideReason("benchmark configuration " + c.label());
        if (c.policy() != null) {
            options = options.withPolicy(c.policy());
        }
        if (c.partitionsPerSlot() > 0) {
            options = options.withPartitionsPerSlot(c.partitionsPerSlot());
        }
        long start = System.nanoTime();
        TaskHandle handle = coordinator.submit(spec, options);
        TaskOutcome outcome;
        try {
            outcome = handle.await(Duration.ofSeconds(settings.runTimeoutSeconds()));
        } catch (TimeoutException e) {
            handle.cancel();
            return new Sample(experiment.id(), c.label(), c.workload().name(), c.mode(), c.cores(), run, warmup,
                    taskId, "TIMED_OUT", false, null, System.nanoTime() - start, 0, null, null,
                    "timed out after " + settings.runTimeoutSeconds() + " s");
        }
        long wall = System.nanoTime() - start;
        TaskResult result = outcome.result();
        boolean verified = result.verification() != null && result.verification().passed();
        String digest = result.result() == null ? null : result.result().digest();
        String error = result.error() == null ? null : result.error().code() + ": " + result.error().message();
        return new Sample(experiment.id(), c.label(), c.workload().name(), c.mode(), c.cores(), run, warmup, taskId,
                result.status().name(), verified, digest, wall, result.partitions(), outcome.snapshot().metrics(),
                result.metrics(), error);
    }

    private static String check(Sample sample, Map<String, String> digests) {
        String where = sample.experiment() + " / " + sample.configuration() + " run " + sample.run();
        if (!"COMPLETED".equals(sample.status())) {
            return where + ": status " + sample.status() + (sample.error() == null ? "" : " (" + sample.error() + ")");
        }
        if (!sample.verified()) {
            return where + ": Core's verification did not pass";
        }
        String expected = digests.putIfAbsent(sample.workload(), sample.digest());
        if (expected != null && !expected.equals(sample.digest())) {
            return where + ": digest " + sample.digest() + " differs from " + expected + " of an earlier run of "
                    + sample.workload();
        }
        return null;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Map<String, Object> environment(Settings settings) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("startedAt", Instant.now().toString());
        env.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " ("
                + System.getProperty("os.arch") + ")");
        env.put("cpuModel", cpuModel());
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            env.put("physicalMemoryBytes", os.getTotalMemorySize());
        }
        env.put("systemLoadAverageAtStart", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
        env.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.runtime.version") + " ("
                + System.getProperty("java.vendor") + ")");
        env.put("coordinatorMaxHeapBytes", Runtime.getRuntime().maxMemory());
        env.put("workerMaxHeapBytes", (long) settings.workerHeapMegabytes() << 20);
        env.put("runtimeVersion", DistributedVersion.version());
        env.put("coreVersion", CoreVersion.version());
        env.put("network", "loopback (coordinator and workers on this machine)");
        return env;
    }

    private static String cpuModel() {
        Path cpuinfo = Path.of("/proc/cpuinfo");
        if (Files.isReadable(cpuinfo)) {
            try {
                for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).strip();
                    }
                }
            } catch (IOException e) {
                return "unknown (" + e.getMessage() + ")";
            }
        }
        String fromEnv = System.getenv("PROCESSOR_IDENTIFIER");
        return fromEnv != null ? fromEnv : "unknown";
    }
}
