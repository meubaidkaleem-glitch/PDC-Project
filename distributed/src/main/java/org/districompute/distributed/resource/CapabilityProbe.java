package org.districompute.distributed.resource;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.districompute.core.CoreVersion;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.GpuStatus;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.protocol.AlgorithmSupport;
import org.districompute.distributed.protocol.GpuInfo;
import org.districompute.distributed.protocol.WorkerCapabilities;

/**
 * Detects what this machine and JVM can do, for the worker's {@code REGISTER} message. Hardware values come from
 * the JVM, algorithms from Core's registry and GPU availability from Core's detection; nothing is assumed.
 */
public final class CapabilityProbe {

    private CapabilityProbe() {
    }

    /**
     * Builds the capabilities of this worker.
     *
     * @param registry Core algorithms installed on the worker
     * @param allowedAlgorithms if not empty, only these algorithm ids are offered
     * @param hostname host name to report
     * @param maxConcurrentTasks configured concurrency limit
     * @param maxTaskMemoryBytes configured memory per attempt
     * @param maxMessageBytes largest frame the worker accepts
     * @param labels configured labels
     * @param faultSimulation whether fault injection is active
     * @return the capabilities
     */
    public static WorkerCapabilities probe(AlgorithmRegistry registry, Set<String> allowedAlgorithms,
            String hostname, int maxConcurrentTasks, long maxTaskMemoryBytes, int maxMessageBytes,
            Map<String, String> labels, boolean faultSimulation) {
        List<AlgorithmSupport> algorithms = registry.catalog().stream()
                .filter(info -> info.capabilities().distributable())
                .filter(info -> allowedAlgorithms.isEmpty()
                        || allowedAlgorithms.contains(info.descriptor().id().value()))
                .map(CapabilityProbe::support).toList();
        Runtime runtime = Runtime.getRuntime();
        return new WorkerCapabilities(WorkerCapabilities.SCHEMA_VERSION, hostname, System.getProperty("os.name"),
                System.getProperty("os.arch"), System.getProperty("java.version"), DistributedVersion.version(),
                CoreVersion.version(), runtime.availableProcessors(), runtime.maxMemory(), totalPhysicalMemory(),
                maxConcurrentTasks, maxTaskMemoryBytes, maxMessageBytes, algorithms, gpu(registry.gpuStatus()),
                labels, faultSimulation);
    }

    /**
     * Converts Core's GPU detection to the protocol's summary. Only backends Core reports as available appear.
     *
     * @param status Core's detection result
     * @return the summary
     */
    public static GpuInfo gpu(GpuStatus status) {
        List<String> backends = status.available().stream().map(b -> b.name()).toList();
        return new GpuInfo(!backends.isEmpty(), backends, backends.isEmpty() ? status.unavailableReasons()
                : List.of());
    }

    /**
     * Physical memory of the machine.
     *
     * @return bytes, or {@code -1} if the JVM cannot tell
     */
    public static long totalPhysicalMemory() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            return extended.getTotalMemorySize();
        }
        return -1;
    }

    /**
     * The machine's one-minute load average.
     *
     * @return the load average, or {@code -1} if unavailable
     */
    public static double systemLoadAverage() {
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return load < 0 ? -1 : load;
    }

    /**
     * CPU load of this process.
     *
     * @return a value in [0, 1], or {@code -1} if unavailable
     */
    public static double processCpuLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load = extended.getProcessCpuLoad();
            return load < 0 ? -1 : load;
        }
        return -1;
    }

    /**
     * Free heap of this JVM (maximum heap minus used heap).
     *
     * @return bytes
     */
    public static long freeHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
    }

    private static AlgorithmSupport support(AlgorithmInfo info) {
        return new AlgorithmSupport(info.descriptor().ref(), List.of(ExecutionMode.DISTRIBUTED_CPU));
    }

    /**
     * Whether an algorithm id is offered under an allow list.
     *
     * @param allowed allow list (empty means all)
     * @param id algorithm id
     * @return {@code true} if offered
     */
    public static boolean allowed(Set<String> allowed, AlgorithmId id) {
        return allowed.isEmpty() || allowed.contains(id.value());
    }
}
