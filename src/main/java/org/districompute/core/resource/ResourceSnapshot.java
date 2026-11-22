package org.districompute.core.resource;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Resources available for a task, as input to strategy assessment. Core can describe only its own node
 * ({@link #localOnly()}). The distributed runtime supplies registered workers and network estimates, because Core
 * never invents remote resources.
 *
 * @param schemaVersion contract schema version
 * @param capturedAt when the snapshot was taken
 * @param local the node that would run local modes
 * @param workers registered remote workers (empty if none or unknown)
 * @param network network between coordinator and workers, or {@code null} if unknown
 * @param gpuDevices GPU devices usable through a detected backend (empty if none)
 */
public record ResourceSnapshot(
        String schemaVersion,
        Instant capturedAt,
        Node local,
        List<Node> workers,
        Network network,
        List<GpuDevice> gpuDevices) {

    /** Validates the components. */
    public ResourceSnapshot {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(local, "local");
        workers = workers == null ? List.of() : List.copyOf(workers);
        gpuDevices = gpuDevices == null ? List.of() : List.copyOf(gpuDevices);
    }

    /**
     * Compute resources of one node.
     *
     * @param nodeId node identifier
     * @param cpuCores processors usable by DistriCompute
     * @param memoryBytes memory usable by a task (for the local node: free heap)
     */
    public record Node(String nodeId, int cpuCores, long memoryBytes) {

        /** Validates the components. */
        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            if (cpuCores < 1 || memoryBytes < 0) {
                throw new IllegalArgumentException("A node needs at least one core and non-negative memory");
            }
        }
    }

    /**
     * Network estimate between coordinator and workers.
     *
     * @param bandwidthBytesPerSecond sustained bandwidth
     * @param latencyMillis round-trip latency per message
     */
    public record Network(double bandwidthBytesPerSecond, double latencyMillis) {

        /** Validates the components. */
        public Network {
            if (!(bandwidthBytesPerSecond > 0) || !(latencyMillis >= 0)) {
                throw new IllegalArgumentException("Bandwidth must be positive and latency non-negative");
            }
        }
    }

    /**
     * Describes this JVM only: available processors and currently free heap, with no workers and no GPU.
     *
     * @return the snapshot
     */
    public static ResourceSnapshot localOnly() {
        Runtime runtime = Runtime.getRuntime();
        long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        return new ResourceSnapshot(SchemaVersion.CURRENT, Instant.now(),
                new Node("local", runtime.availableProcessors(), free), List.of(), null, List.of());
    }
}
