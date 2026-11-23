package org.districompute.distributed.coordinator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.TaskPreparation;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.resource.ResourceSnapshot;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.scheduling.Calibration;
import org.districompute.core.scheduling.StrategyAdvisor;
import org.districompute.core.scheduling.StrategyAssessment;
import org.districompute.core.scheduling.StrategyRecommendation;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskSpec;
import org.districompute.distributed.registry.PerformanceStats;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.resource.CapabilityProbe;
import org.districompute.distributed.scheduler.Eligibility;
import org.districompute.distributed.scheduler.Rejection;
import org.districompute.distributed.scheduler.TaskRequirements;
import org.districompute.distributed.strategy.ExecutionAdvice;
import org.districompute.distributed.strategy.RuntimeContext;
import org.districompute.distributed.strategy.StrategyOverride;

/**
 * Cooperates with Core's {@link StrategyAdvisor}: Core supplies the cost model and the algorithm's workload profile;
 * this runtime supplies the resources (workers, network, load) and the history. Runs on the control loop.
 */
final class StrategyService {

    private static final int MAX_CALIBRATIONS = 1000;

    private final CoordinatorConfig config;
    private final WorkerManager workers;
    private final StrategyAdvisor advisor = new StrategyAdvisor(StrategyAdvisor.Settings.defaults());
    private final Deque<Calibration> supplied = new ArrayDeque<>();
    private final Deque<Calibration> measured = new ArrayDeque<>();
    private long minTransferNanos = -1;

    StrategyService(CoordinatorConfig config, WorkerManager workers) {
        this.config = config;
        this.workers = workers;
    }

    /**
     * Assesses every mode for a task with the cluster's current resources.
     *
     * @param spec the task (its mode does not matter)
     * @param pendingPartitions partitions of other tasks waiting
     * @return the advice
     * @throws org.districompute.core.DistriComputeException for unknown algorithms or invalid parameters
     */
    ExecutionAdvice advise(TaskSpec spec, int pendingPartitions) {
        ComputeAlgorithm<?, ?> algorithm = config.registry().resolve(spec.algorithm());
        AlgorithmInfo info = config.registry().info(algorithm.descriptor().id()).orElseThrow();
        Parameters parameters = TaskPreparation.validate(algorithm, spec, info.capabilities().distributable());
        WorkloadProfile profile = algorithm.analyze(parameters);
        TaskRequirements probe = new TaskRequirements(profile.algorithm(), 0, 0, 0, TaskPriority.NORMAL);

        List<String> eligible = new ArrayList<>();
        List<Rejection> ineligible = new ArrayList<>();
        List<ResourceSnapshot.Node> nodes = new ArrayList<>();
        int totalSlots = 0;
        int busySlots = 0;
        for (WorkerRecord worker : workers.registry().connected()) {
            Optional<String> reason = worker.status() != WorkerStatus.ACTIVE
                    ? Optional.of("status " + worker.status()) : Eligibility.incapable(worker.capabilities(), probe);
            if (reason.isPresent()) {
                ineligible.add(new Rejection(worker.workerId(), reason.get()));
                continue;
            }
            eligible.add(worker.workerId());
            int slots = worker.maxConcurrentTasks();
            totalSlots += slots;
            busySlots += worker.assignedAttempts().size();
            nodes.add(new ResourceSnapshot.Node(worker.workerId(), Math.min(worker.capabilities().cpuCores(), slots),
                    worker.capabilities().maxTaskMemoryBytes() * slots));
        }
        Double bandwidth = medianTransferRate();
        Double latency = minTransferNanos < 0 ? null : minTransferNanos / 1e6;
        ResourceSnapshot.Network network = bandwidth == null || latency == null ? null
                : new ResourceSnapshot.Network(bandwidth, latency);
        Runtime runtime = Runtime.getRuntime();
        ResourceSnapshot resources = new ResourceSnapshot(SchemaVersion.CURRENT, config.clock().instant(),
                new ResourceSnapshot.Node("coordinator", runtime.availableProcessors(), CapabilityProbe.freeHeap()),
                nodes, network, List.of());
        StrategyRecommendation recommendation = advisor.recommend(profile, info.capabilities(), resources,
                calibrations(), config.registry().gpuStatus());

        List<ExecutionMode> executable = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        executable.add(ExecutionMode.SEQUENTIAL_CPU);
        if (info.capabilities().supports(ExecutionMode.MULTITHREADED_CPU)) {
            executable.add(ExecutionMode.MULTITHREADED_CPU);
        }
        if (!info.capabilities().distributable()) {
            notes.add(profile.algorithm().id() + " cannot be partitioned, so it cannot run on workers");
        } else if (eligible.isEmpty()) {
            notes.add("No connected worker can run " + profile.algorithm() + " now; a distributed task would wait up "
                    + "to " + config.tasks().noWorkerTimeout().toSeconds() + " s for one");
        } else {
            executable.add(ExecutionMode.DISTRIBUTED_CPU);
        }
        notes.add("GPU: " + config.registry().gpuStatus().summary() + "; no worker reports a GPU backend");
        notes.add("Hybrid CPU/GPU/distributed execution is not implemented");
        if (network == null) {
            notes.add("The network has not been measured yet (it is measured from completed distributed attempts), "
                    + "so Core cannot estimate communication time");
        }
        long transferBytes = (long) ((profile.inputBytes() + profile.outputBytes()) * 4.0 / 3);
        RuntimeContext context = new RuntimeContext(eligible, ineligible, totalSlots, busySlots, pendingPartitions,
                bandwidth, latency, transferBytes, executable, notes);
        return new ExecutionAdvice(config.clock().instant(), profile.algorithm(), recommendation.recommended(),
                explain(recommendation, context), recommendation, resources, context);
    }

    /**
     * Compares a requested mode with the current recommendation.
     *
     * @param spec the submitted task
     * @param reason the user's reason, or {@code null}
     * @param pendingPartitions partitions waiting
     * @return the override, or empty if the mode is the recommended one or no advice could be computed
     */
    Optional<StrategyOverride> overrideFor(TaskSpec spec, String reason, int pendingPartitions) {
        ExecutionAdvice advice;
        try {
            advice = advise(spec, pendingPartitions);
        } catch (RuntimeException e) {
            // Invalid tasks fail later with Core's validation error; there is nothing to compare.
            return Optional.empty();
        }
        ExecutionMode chosen = spec.constraints().mode();
        if (chosen == advice.recommended()) {
            return Optional.empty();
        }
        StrategyAssessment assessment = advice.assessment().assessment(chosen).orElseThrow();
        return Optional.of(new StrategyOverride(config.clock().instant(), advice.recommended(), chosen,
                assessment.verdict(), assessment.reasons(), reason));
    }

    /**
     * Records the transport time of an accepted attempt (the smallest one bounds the latency).
     *
     * @param transferNanos round trip minus worker residence
     */
    void observeTransfer(long transferNanos) {
        if (transferNanos > 0 && (minTransferNanos < 0 || transferNanos < minTransferNanos)) {
            minTransferNanos = transferNanos;
        }
    }

    /**
     * Records a throughput measured by this runtime.
     *
     * @param calibration the measurement
     */
    void recordMeasured(Calibration calibration) {
        add(measured, calibration);
    }

    /**
     * Adds throughput history supplied by the Application (for example from Core benchmark reports).
     *
     * @param calibrations the measurements
     */
    void addSupplied(Collection<Calibration> calibrations) {
        calibrations.forEach(c -> add(supplied, c));
    }

    /**
     * All history used for advice: supplied first, then measured.
     *
     * @return the calibrations
     */
    List<Calibration> calibrations() {
        List<Calibration> all = new ArrayList<>(supplied);
        all.addAll(measured);
        return all;
    }

    private Double medianTransferRate() {
        double[] rates = workers.registry().connected().stream().flatMap(w -> w.history().all().stream())
                .mapToDouble(PerformanceStats::transferBytesPerSecond).filter(r -> r > 0).sorted().toArray();
        if (rates.length == 0) {
            return null;
        }
        return rates.length % 2 == 1 ? rates[rates.length / 2]
                : (rates[rates.length / 2 - 1] + rates[rates.length / 2]) / 2;
    }

    private static void add(Deque<Calibration> list, Calibration calibration) {
        list.addLast(calibration);
        if (list.size() > MAX_CALIBRATIONS) {
            list.removeFirst();
        }
    }

    private static String explain(StrategyRecommendation recommendation, RuntimeContext context) {
        StringBuilder text = new StringBuilder();
        if (recommendation.recommended() == null) {
            text.append("No mode is suitable for this task with the current resources.");
        } else {
            StrategyAssessment best = recommendation.assessment(recommendation.recommended()).orElseThrow();
            text.append("Recommended: ").append(recommendation.recommended()).append(" (")
                    .append(String.join("; ", best.reasons())).append(").");
        }
        recommendation.assessment(ExecutionMode.DISTRIBUTED_CPU).ifPresent(d -> {
            if (d.mode() != recommendation.recommended()) {
                text.append(" Distributed: ").append(d.verdict()).append(" (")
                        .append(String.join("; ", d.reasons())).append(").");
            }
        });
        text.append(String.format(Locale.ROOT, " %d eligible workers, %d of %d slots busy.",
                context.eligibleWorkers().size(), context.busySlots(), context.totalSlots()));
        if (!recommendation.calibrated()) {
            text.append(" Estimates are uncalibrated (no measured history for this algorithm).");
        }
        return text.toString();
    }

    /**
     * When the history was last changed, for snapshots.
     *
     * @return now
     */
    Instant now() {
        return config.clock().instant();
    }
}
