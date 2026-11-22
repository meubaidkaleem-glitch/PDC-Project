package org.districompute.distributed.protocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;

/**
 * What a worker reports about itself at registration ({@code docs/worker-lifecycle.md}). Hardware values are
 * detected, not configured, except for the execution limits.
 *
 * @param schemaVersion capability schema version ({@link #SCHEMA_VERSION}; same rules as the protocol version)
 * @param hostname host name of the worker machine
 * @param osName operating system name
 * @param osArch processor architecture
 * @param javaVersion Java runtime version
 * @param runtimeVersion DistriCompute-Distributed version of the worker
 * @param coreVersion DistriCompute-Core version of the worker
 * @param cpuCores processors available to the worker's JVM
 * @param maxHeapBytes maximum heap of the worker's JVM
 * @param totalMemoryBytes physical memory of the machine, or {@code -1} if unknown
 * @param maxConcurrentTasks attempts the worker executes at the same time
 * @param maxTaskMemoryBytes memory one attempt may use
 * @param maxMessageBytes largest frame the worker accepts
 * @param algorithms algorithms and versions the worker can execute
 * @param gpu GPU availability detected by Core
 * @param labels free-form labels from the worker's configuration (at most 32)
 * @param faultSimulation whether fault injection is active on this worker (test experiments only)
 */
public record WorkerCapabilities(
        String schemaVersion,
        String hostname,
        String osName,
        String osArch,
        String javaVersion,
        String runtimeVersion,
        String coreVersion,
        int cpuCores,
        long maxHeapBytes,
        long totalMemoryBytes,
        int maxConcurrentTasks,
        long maxTaskMemoryBytes,
        int maxMessageBytes,
        List<AlgorithmSupport> algorithms,
        GpuInfo gpu,
        Map<String, String> labels,
        boolean faultSimulation) {

    /** Capability schema version implemented by this runtime. */
    public static final String SCHEMA_VERSION = "0.1";
    /** Upper bound on {@link #maxConcurrentTasks()}. */
    public static final int MAX_CONCURRENT_TASKS = 1024;

    private static final Pattern LABEL_KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    /** Validates the components. */
    public WorkerCapabilities {
        if (!ProtocolVersion.isSupported(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported capability schemaVersion " + schemaVersion
                    + " (this runtime reads " + SCHEMA_VERSION + ")");
        }
        Checks.requireText(hostname, "hostname", 255);
        Checks.optionalText(osName, "osName", 128);
        Checks.optionalText(osArch, "osArch", 64);
        Checks.optionalText(javaVersion, "javaVersion", 64);
        Checks.optionalText(runtimeVersion, "runtimeVersion", 64);
        Checks.optionalText(coreVersion, "coreVersion", 64);
        if (cpuCores < 1) {
            throw new IllegalArgumentException("cpuCores must be at least 1");
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > MAX_CONCURRENT_TASKS) {
            throw new IllegalArgumentException("maxConcurrentTasks must be between 1 and " + MAX_CONCURRENT_TASKS);
        }
        if (maxHeapBytes < 1 || maxTaskMemoryBytes < 1) {
            throw new IllegalArgumentException("maxHeapBytes and maxTaskMemoryBytes must be positive");
        }
        if (maxMessageBytes < 1024) {
            throw new IllegalArgumentException("maxMessageBytes must be at least 1024");
        }
        algorithms = Checks.boundedCopy(algorithms, "algorithms", 1024);
        gpu = gpu == null ? new GpuInfo(false, List.of(), List.of("not reported")) : gpu;
        labels = copyLabels(labels);
    }

    /**
     * The support entry for an algorithm id.
     *
     * @param id algorithm id
     * @return the entry, if the worker reports the algorithm
     */
    public Optional<AlgorithmSupport> algorithm(AlgorithmId id) {
        return algorithms.stream().filter(a -> a.algorithm().id().equals(id)).findFirst();
    }

    /**
     * Whether the worker reports an algorithm in a mode.
     *
     * @param id algorithm id
     * @param mode execution mode
     * @return {@code true} if supported
     */
    public boolean supports(AlgorithmId id, ExecutionMode mode) {
        return algorithm(id).map(a -> a.supports(mode)).orElse(false);
    }

    private static Map<String, String> copyLabels(Map<String, String> labels) {
        if (labels == null) {
            return Map.of();
        }
        if (labels.size() > 32) {
            throw new IllegalArgumentException("At most 32 labels are allowed");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        labels.forEach((key, value) -> {
            if (key == null || !LABEL_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("Label keys must be 1-64 characters [A-Za-z0-9._-]");
            }
            copy.put(key, Checks.requireText(value, "label " + key, 256));
        });
        return java.util.Collections.unmodifiableMap(copy);
    }
}
