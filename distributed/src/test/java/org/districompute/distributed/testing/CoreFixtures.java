package org.districompute.distributed.testing;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.districompute.core.CoreVersion;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.partition.PartialResultMessage;
import org.districompute.core.partition.PartitionWorker;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.task.TaskSpec;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.protocol.AlgorithmSupport;
import org.districompute.distributed.protocol.GpuInfo;
import org.districompute.distributed.protocol.WorkerCapabilities;

/** Real Core objects for tests: tasks, sessions, work units and partial results produced by Core itself. */
public final class CoreFixtures {

    private static final AlgorithmRegistry REGISTRY = AlgorithmRegistry.loadDefault();

    private CoreFixtures() {
    }

    /**
     * The built-in Core registry.
     *
     * @return the registry
     */
    public static AlgorithmRegistry registry() {
        return REGISTRY;
    }

    /**
     * A distributed prime-counting task.
     *
     * @param taskId task id
     * @param upperBound exclusive upper bound
     * @param partitions number of partitions
     * @return the task
     */
    public static TaskSpec primesTask(String taskId, long upperBound, int partitions) {
        return TaskSpec.builder("primes.count").taskId(taskId).experimentId("exp-test")
                .parameter("lowerBound", 0).parameter("upperBound", upperBound)
                .mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(partitions, null, null, null))
                .createdAt(Instant.parse("2026-11-22T10:00:00Z")).build();
    }

    /**
     * Opens a Core session for a distributed task.
     *
     * @param spec the task
     * @return the session
     */
    public static PartitionedTaskSession session(TaskSpec spec) {
        return PartitionedTaskSession.open(REGISTRY, spec, 4);
    }

    /**
     * Executes a work unit with Core's worker facade.
     *
     * @param unit the work unit
     * @param workerId worker id written into the result
     * @return Core's partial result
     */
    public static PartialResultMessage execute(WorkUnitMessage unit, String workerId) {
        return new PartitionWorker(REGISTRY, workerId).execute(unit, CancellationToken.NONE, ProgressReporter.NONE);
    }

    /**
     * Capabilities derived from the built-in registry, as a worker would report them.
     *
     * @param maxConcurrentTasks the worker's limit
     * @return the capabilities
     */
    public static WorkerCapabilities capabilities(int maxConcurrentTasks) {
        List<AlgorithmSupport> algorithms = REGISTRY.catalog().stream()
                .filter(info -> info.capabilities().distributable())
                .map(CoreFixtures::support).toList();
        return new WorkerCapabilities(WorkerCapabilities.SCHEMA_VERSION, "test-host", "Linux", "amd64", "21",
                DistributedVersion.version(), CoreVersion.version(), 4, 1L << 30, -1, maxConcurrentTasks,
                256L << 20, 16 << 20, algorithms, new GpuInfo(false, List.of(), List.of("no GPU backend")),
                Map.of("rack", "test"), false);
    }

    private static AlgorithmSupport support(AlgorithmInfo info) {
        return new AlgorithmSupport(info.descriptor().ref(), List.of(ExecutionMode.DISTRIBUTED_CPU));
    }
}
