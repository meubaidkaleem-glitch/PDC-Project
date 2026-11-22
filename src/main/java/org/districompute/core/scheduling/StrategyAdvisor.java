package org.districompute.core.scheduling;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.registry.AlgorithmCapabilities;
import org.districompute.core.resource.DataDependency;
import org.districompute.core.resource.GpuStatus;
import org.districompute.core.resource.ResourceSnapshot;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.scheduling.StrategyAssessment.Verdict;

/**
 * Evaluates every execution mode for a workload, given the resources supplied by the caller and, optionally,
 * measured throughput. It recommends the fastest suitable mode and states why the others are unsupported or
 * unsuitable. No strategy is assumed to be universally best.
 *
 * <p>The cost model is deliberately simple and explicit:
 * <ul>
 *   <li>sequential time {@code T1 = operations / calibrated throughput} (no estimate without calibration);</li>
 *   <li>multithreaded time: calibrated throughput for that thread count, or else
 *       {@code T1 / (threads × efficiency) + partitions × perPartitionOverhead};</li>
 *   <li>distributed time: {@code T1 / (worker cores × efficiency) + (input + output bytes) / bandwidth +
 *       messages × latency}. Input plus output is a lower bound on traffic, because some decompositions replicate
 *       data, for example B for every row band;</li>
 *   <li>a workload below {@code smallWorkloadSeconds} (calibrated) or {@code smallWorkloadOperations}
 *       (uncalibrated) is not worth parallelizing. The default threshold comes from measurements: a 128³ matrix
 *       multiplication (4·10⁶ flops) ran 0.88× with 2 threads, and 256³ (3.4·10⁷ flops) ran 1.89× with 4 threads,
 *       on the development container.</li>
 * </ul>
 * Every assessment lists its reasons. The recommendation lists the assumptions used.
 */
public final class StrategyAdvisor {

    private final Settings settings;

    /**
     * Thresholds and default assumptions of the cost model.
     *
     * @param smallWorkloadOperations uncalibrated: below this many estimated operations, stay sequential
     * @param smallWorkloadSeconds calibrated: below this estimated sequential time, stay sequential
     * @param parallelEfficiency assumed efficiency of independent partitions without calibration
     * @param orderedMergeEfficiency assumed efficiency when partials need an ordered (sequential) merge
     * @param perPartitionOverheadSeconds assumed dispatch and aggregation cost per partition
     * @param memoryHeadroom fraction of a node's memory a task may plan to use
     */
    public record Settings(double smallWorkloadOperations, double smallWorkloadSeconds, double parallelEfficiency,
            double orderedMergeEfficiency, double perPartitionOverheadSeconds, double memoryHeadroom) {

        /**
         * Defaults, documented in {@code docs/execution-model.md}.
         *
         * @return the default settings
         */
        public static Settings defaults() {
            return new Settings(1e7, 0.02, 0.75, 0.4, 5e-5, 0.8);
        }
    }

    /**
     * Creates an advisor.
     *
     * @param settings thresholds and default assumptions
     */
    public StrategyAdvisor(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Assesses all modes.
     *
     * @param profile workload profile from {@code ComputeAlgorithm.analyze}
     * @param capabilities derived capabilities from the registry
     * @param resources resources supplied by the caller ({@link ResourceSnapshot#localOnly()} for Core alone)
     * @param history measured throughput, possibly empty
     * @param gpu GPU detection result
     * @return verdicts and the recommendation
     */
    public StrategyRecommendation recommend(WorkloadProfile profile, AlgorithmCapabilities capabilities,
            ResourceSnapshot resources, List<Calibration> history, GpuStatus gpu) {
        AlgorithmId id = profile.algorithm().id();
        double operations = profile.estimatedOperations();
        int cores = resources.local().cpuCores();
        Double sequentialRate = rate(history, id, ExecutionMode.SEQUENTIAL_CPU, 1);
        Double sequentialSeconds = sequentialRate == null ? null : operations / sequentialRate;
        boolean small = sequentialSeconds != null ? sequentialSeconds < settings.smallWorkloadSeconds()
                : operations < settings.smallWorkloadOperations();
        long localBudget = (long) (resources.local().memoryBytes() * settings.memoryHeadroom());
        boolean fitsLocally = profile.estimatedPeakMemoryBytes() <= localBudget;
        double efficiency = profile.parallelism().dataDependency() == DataDependency.ORDERED_MERGE
                ? settings.orderedMergeEfficiency() : settings.parallelEfficiency();
        List<String> assumptions = new ArrayList<>();
        if (sequentialRate == null) {
            assumptions.add("No calibration for " + id + ": no time estimates; small-workload threshold "
                    + fmt(settings.smallWorkloadOperations()) + " estimated operations");
        }

        List<StrategyAssessment> assessments = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        if (!fitsLocally) {
            reasons.add(memoryReason(profile.estimatedPeakMemoryBytes(), localBudget, "local"));
        }
        assessments.add(new StrategyAssessment(ExecutionMode.SEQUENTIAL_CPU,
                fitsLocally ? Verdict.SUITABLE : Verdict.UNSUITABLE, 1, sequentialSeconds,
                reasons.isEmpty() ? List.of("Reference implementation; no partitioning overhead") : reasons));

        assessments.add(assessMultithreaded(profile, capabilities, history, cores, sequentialSeconds, small,
                fitsLocally, localBudget, efficiency, assumptions));
        assessments.add(assessDistributed(profile, capabilities, resources, sequentialSeconds, small, efficiency,
                assumptions));
        assessments.add(capabilities.gpu()
                ? new StrategyAssessment(ExecutionMode.GPU, Verdict.SUITABLE, null, null,
                        List.of("A detected GPU backend supports this algorithm"))
                : new StrategyAssessment(ExecutionMode.GPU, Verdict.UNSUPPORTED, null, null,
                        List.of("No GPU implementation: " + gpu.summary())));
        assessments.add(new StrategyAssessment(ExecutionMode.HYBRID, Verdict.UNSUPPORTED, null, null,
                List.of("Hybrid CPU/GPU/distributed execution is not implemented")));

        ExecutionMode recommended = choose(assessments, small);
        List<StrategyAssessment> marked = assessments.stream()
                .map(a -> a.mode() == recommended ? a.withVerdict(Verdict.RECOMMENDED) : a).toList();
        return new StrategyRecommendation(profile.algorithm(), recommended, marked, sequentialRate != null,
                assumptions);
    }

    private StrategyAssessment assessMultithreaded(WorkloadProfile profile, AlgorithmCapabilities capabilities,
            List<Calibration> history, int cores, Double sequentialSeconds, boolean small, boolean fitsLocally,
            long localBudget, double efficiency, List<String> assumptions) {
        ExecutionMode mode = ExecutionMode.MULTITHREADED_CPU;
        if (!capabilities.supports(mode)) {
            return new StrategyAssessment(mode, Verdict.UNSUPPORTED, null, null,
                    List.of("The algorithm cannot be partitioned"));
        }
        List<String> reasons = new ArrayList<>();
        if (cores < 2) {
            reasons.add("Only one processor is available");
        }
        if (!fitsLocally) {
            reasons.add(memoryReason(profile.estimatedPeakMemoryBytes(), localBudget, "local"));
        }
        if (small) {
            reasons.add("Workload too small: partitioning and scheduling overhead would likely exceed the gain");
        }
        Double rate = rate(history, profile.algorithm().id(), mode, cores);
        Double estimate = null;
        if (rate != null) {
            estimate = profile.estimatedOperations() / rate;
        } else if (sequentialSeconds != null) {
            long partitions = Math.min(profile.parallelism().maxUsefulPartitions(), 4L * cores);
            estimate = sequentialSeconds / (cores * efficiency) + partitions * settings.perPartitionOverheadSeconds();
            assumptions.add(String.format(Locale.ROOT, "Multithreaded estimate assumes efficiency %.2f and %.0f µs "
                    + "per partition", efficiency, settings.perPartitionOverheadSeconds() * 1e6));
        }
        if (reasons.isEmpty()) {
            reasons.add("Independent partitions on " + cores + " threads ("
                    + profile.parallelism().dataDependency() + " aggregation: "
                    + profile.parallelism().aggregationComplexity() + ")");
            return new StrategyAssessment(mode, Verdict.SUITABLE, cores, estimate, reasons);
        }
        return new StrategyAssessment(mode, Verdict.UNSUITABLE, cores, estimate, reasons);
    }

    private StrategyAssessment assessDistributed(WorkloadProfile profile, AlgorithmCapabilities capabilities,
            ResourceSnapshot resources, Double sequentialSeconds, boolean small, double efficiency,
            List<String> assumptions) {
        ExecutionMode mode = ExecutionMode.DISTRIBUTED_CPU;
        if (!capabilities.distributable()) {
            return new StrategyAssessment(mode, Verdict.UNSUPPORTED, null, null,
                    List.of("The algorithm cannot be partitioned for remote workers"));
        }
        if (resources.workers().isEmpty()) {
            return new StrategyAssessment(mode, Verdict.UNSUITABLE, null, null,
                    List.of("No workers registered; the distributed runtime must supply them"));
        }
        int workerCores = resources.workers().stream().mapToInt(ResourceSnapshot.Node::cpuCores).sum();
        long perWorker = profile.estimatedPeakMemoryBytes() / resources.workers().size();
        long smallestWorker = resources.workers().stream().mapToLong(ResourceSnapshot.Node::memoryBytes).min()
                .orElse(0);
        List<String> reasons = new ArrayList<>();
        if (perWorker > smallestWorker * settings.memoryHeadroom()) {
            reasons.add(memoryReason(perWorker, (long) (smallestWorker * settings.memoryHeadroom()), "per-worker"));
        }
        if (small) {
            reasons.add("Workload too small to amortize communication and remote scheduling");
        }
        Double estimate = null;
        if (resources.network() == null) {
            reasons.add("No network estimate supplied; communication cost is unknown");
        } else if (sequentialSeconds != null) {
            double bytes = profile.inputBytes() + profile.outputBytes();
            long messages = 2L * Math.min(profile.parallelism().maxUsefulPartitions(), 2L * workerCores);
            double communication = bytes / resources.network().bandwidthBytesPerSecond()
                    + messages * resources.network().latencyMillis() / 1000;
            estimate = sequentialSeconds / (workerCores * efficiency) + communication;
            assumptions.add("Distributed estimate counts input + output bytes once (a lower bound on traffic)");
            if (communication >= sequentialSeconds) {
                reasons.add(String.format(Locale.ROOT, "Estimated communication (%.3f s) exceeds the sequential "
                        + "compute estimate (%.3f s)", communication, sequentialSeconds));
            }
        }
        boolean blocking = reasons.stream().anyMatch(r -> !r.startsWith("No network estimate"));
        if (!blocking) {
            reasons.add(resources.workers().size() + " workers with " + workerCores + " cores");
            return new StrategyAssessment(mode, Verdict.SUITABLE, workerCores, estimate, reasons);
        }
        return new StrategyAssessment(mode, Verdict.UNSUITABLE, workerCores, estimate, reasons);
    }

    private static ExecutionMode choose(List<StrategyAssessment> assessments, boolean small) {
        List<StrategyAssessment> suitable = assessments.stream().filter(a -> a.verdict() == Verdict.SUITABLE)
                .toList();
        if (suitable.isEmpty()) {
            return null;
        }
        Optional<StrategyAssessment> fastest = suitable.stream().filter(a -> a.estimatedSeconds() != null)
                .min(Comparator.comparingDouble(StrategyAssessment::estimatedSeconds));
        if (fastest.isPresent() && suitable.stream().allMatch(a -> a.estimatedSeconds() != null)) {
            return fastest.get().mode();
        }
        List<ExecutionMode> preference = small
                ? List.of(ExecutionMode.SEQUENTIAL_CPU, ExecutionMode.MULTITHREADED_CPU, ExecutionMode.DISTRIBUTED_CPU)
                : List.of(ExecutionMode.MULTITHREADED_CPU, ExecutionMode.SEQUENTIAL_CPU, ExecutionMode.DISTRIBUTED_CPU);
        for (ExecutionMode mode : preference) {
            if (suitable.stream().anyMatch(a -> a.mode() == mode)) {
                return mode;
            }
        }
        return suitable.get(0).mode();
    }

    private static Double rate(List<Calibration> history, AlgorithmId id, ExecutionMode mode, int threads) {
        return history.stream()
                .filter(c -> c.algorithm().equals(id) && c.mode() == mode && c.threads() == threads)
                .mapToDouble(Calibration::operationsPerSecond).average().stream().boxed().findFirst().orElse(null);
    }

    private static String memoryReason(long needed, long budget, String where) {
        return String.format(Locale.ROOT, "Estimated peak memory %.1f MiB exceeds the %s budget of %.1f MiB",
                needed / 1048576.0, where, budget / 1048576.0);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.3g", value);
    }
}
