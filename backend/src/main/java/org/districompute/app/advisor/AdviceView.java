package org.districompute.app.advisor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The execution strategy advice shown before submission.
 *
 * @param createdAt when the advice was computed (resources describe this moment only)
 * @param algorithm Core algorithm id
 * @param workload Core's workload analysis
 * @param recommendedMode recommended mode, or {@code null} if no reliable recommendation can be made
 * @param reliable whether a recommendation can be relied on
 * @param confidence {@code HIGH}, {@code MEDIUM}, {@code LOW} or {@code NONE}
 * @param basis how the recommendation was estimated ({@code MEASURED}, {@code HISTORICAL}, {@code THEORETICAL},
 *     {@code NONE})
 * @param explanation one paragraph explaining the recommendation
 * @param modes every execution mode with its assessment
 * @param resources resources taken into account
 * @param assumptions Core's assumptions
 * @param warnings warnings for the user
 */
public record AdviceView(Instant createdAt, String algorithm, WorkloadView workload, String recommendedMode,
        boolean reliable, String confidence, String basis, String explanation, List<ModeAdvice> modes,
        ResourcesView resources, List<String> assumptions, List<String> warnings) {

    /**
     * Core's workload analysis.
     *
     * @param dimensions problem dimensions
     * @param estimatedOperations estimated operations
     * @param operationUnit what one operation is
     * @param timeComplexity asymptotic complexity
     * @param inputBytes input size
     * @param outputBytes output size
     * @param estimatedPeakMemoryBytes estimated peak memory
     * @param partitionable whether the work can be partitioned
     * @param partitionStrategy partitioning strategy
     * @param maxUsefulPartitions largest useful partition count
     * @param dataDependency dependency between partitions
     * @param aggregation aggregation cost
     */
    public record WorkloadView(Map<String, Long> dimensions, double estimatedOperations, String operationUnit,
            String timeComplexity, long inputBytes, long outputBytes, long estimatedPeakMemoryBytes,
            boolean partitionable, String partitionStrategy, long maxUsefulPartitions, String dataDependency,
            String aggregation) {
    }

    /**
     * One execution mode.
     *
     * @param mode Core mode name
     * @param verdict Core's verdict ({@code RECOMMENDED}, {@code SUITABLE}, {@code NOT_RECOMMENDED},
     *     {@code UNSUPPORTED}, ...)
     * @param executable whether this installation can run the mode now
     * @param threads threads or cores Core assumed
     * @param estimatedSeconds Core's estimate, or {@code null}
     * @param estimateBasis basis of the estimate
     * @param measured this application's measurements of an identical workload in this mode, or {@code null}
     * @param reasons Core's reasons
     */
    public record ModeAdvice(String mode, String verdict, boolean executable, Integer threads, Double estimatedSeconds,
            String estimateBasis, Measured measured, List<String> reasons) {
    }

    /**
     * Measurements of identical workloads (same algorithm and dimensions).
     *
     * @param runs number of completed runs
     * @param medianSeconds median wall time
     * @param minSeconds fastest
     * @param maxSeconds slowest
     */
    public record Measured(int runs, double medianSeconds, double minSeconds, double maxSeconds) {
    }

    /**
     * Resources the advice took into account.
     *
     * @param localCores processors of the application server (local modes run there)
     * @param eligibleWorkers workers that can run the algorithm now
     * @param ineligibleWorkers other connected workers and why they cannot
     * @param totalSlots slots of eligible workers
     * @param busySlots busy slots
     * @param pendingPartitions partitions waiting in the coordinator
     * @param gpuAvailable whether a GPU backend exists
     * @param gpuStatus Core's GPU status
     * @param networkMeasured whether the network has been measured
     * @param transferBytesPerSecond measured transfer rate, or {@code null}
     * @param latencyMillis measured latency, or {@code null}
     * @param estimatedTransferBytes bytes a distributed run would move
     * @param estimatedCommunicationSeconds transfer bytes divided by the measured rate, or {@code null}
     */
    public record ResourcesView(int localCores, List<String> eligibleWorkers, List<Map<String, String>> ineligibleWorkers,
            int totalSlots, int busySlots, int pendingPartitions, boolean gpuAvailable, String gpuStatus,
            boolean networkMeasured, Double transferBytesPerSecond, Double latencyMillis, long estimatedTransferBytes,
            Double estimatedCommunicationSeconds) {
    }
}
