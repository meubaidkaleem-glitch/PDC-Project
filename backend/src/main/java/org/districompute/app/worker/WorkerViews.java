package org.districompute.app.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Bodies of the worker API ({@code docs/api.md#workers}). */
public final class WorkerViews {

    private WorkerViews() {
    }

    /**
     * A worker: the runtime's live view when connected, the application's record always. Capabilities are those the
     * worker reported; nothing is filled in by the application.
     *
     * @param workerId id
     * @param hostname host name reported by the worker
     * @param endpoint address the coordinator sees
     * @param status {@code ACTIVE}, {@code SUSPECT}, {@code DRAINING}, {@code QUARANTINED}, {@code DEACTIVATED},
     *     {@code DISCONNECTED}, or {@code NEVER_CONNECTED} for a registered worker that has not connected yet
     * @param statusReason reason of the latest status change
     * @param connected whether the worker has a live connection
     * @param approved whether an administrator approved it
     * @param deactivated whether an administrator deactivated it
     * @param cpuCores processors
     * @param maxHeapBytes JVM heap limit
     * @param physicalMemoryBytes physical memory ({@code -1} if unknown)
     * @param os operating system and architecture
     * @param javaVersion Java version
     * @param runtimeVersion Distributed version
     * @param coreVersion Core version
     * @param gpuAvailable whether the worker reports a working GPU backend
     * @param gpuDetails GPU backends or the reasons none is available
     * @param algorithms algorithms with versions the worker offers
     * @param labels labels
     * @param faultSimulation whether fault injection is enabled on the worker
     * @param slots concurrent attempts
     * @param assignedTasks attempts assigned now
     * @param activeTasks attempts the worker reports running
     * @param freeMemoryBytes free heap at the last heartbeat
     * @param processCpuLoad process CPU load at the last heartbeat (0-1, negative if unknown)
     * @param systemLoadAverage system load average ({@code -1} if unknown)
     * @param completedTasks attempts completed (as counted by the coordinator)
     * @param failedTasks attempts failed, timed out, lost or invalid
     * @param counters detailed counters
     * @param performance observed throughput per algorithm
     * @param lastHeartbeatAt latest heartbeat
     * @param registeredAt first registration with this application
     * @param lastSeenAt last time it was connected
     * @param notes administrator's notes
     */
    public record WorkerView(String workerId, String hostname, String endpoint, String status, String statusReason,
            boolean connected, boolean approved, boolean deactivated, Integer cpuCores, Long maxHeapBytes,
            Long physicalMemoryBytes, String os, String javaVersion, String runtimeVersion, String coreVersion,
            boolean gpuAvailable, List<String> gpuDetails, List<String> algorithms, Map<String, String> labels,
            boolean faultSimulation, Integer slots, Integer assignedTasks, Integer activeTasks, Long freeMemoryBytes,
            Double processCpuLoad, Double systemLoadAverage, long completedTasks, long failedTasks,
            Map<String, Long> counters, List<Map<String, Object>> performance, Instant lastHeartbeatAt,
            Instant registeredAt, Instant lastSeenAt, String notes) {
    }

    /**
     * Registration of an expected worker by an administrator.
     *
     * @param workerId the id the worker will use ({@code --id})
     * @param notes notes
     */
    public record RegisterWorker(@NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String workerId,
            @Size(max = 500) String notes) {
    }

    /**
     * How to start a worker against this application's coordinator.
     *
     * @param workerId worker id
     * @param coordinatorHost host workers connect to
     * @param coordinatorPort worker port
     * @param tls whether the worker port uses TLS
     * @param approvalRequired whether unapproved workers are deactivated on registration
     * @param commandLine command to run on the worker machine
     * @param configFile contents of a worker properties file (no secret)
     * @param secretInstructions where the shared secret comes from (never the secret itself)
     */
    public record Registration(String workerId, String coordinatorHost, int coordinatorPort, boolean tls,
            boolean approvalRequired, String commandLine, String configFile, String secretInstructions) {
    }

    /**
     * A reason for an administrative action.
     *
     * @param reason reason (recorded in the worker's status and the audit log)
     */
    public record Reason(@Size(max = 300) String reason) {
    }

    /**
     * The outcome of a connection test.
     *
     * @param workerId worker
     * @param coordinatorListening whether the coordinator's worker port accepts connections on this server
     * @param connected whether the worker is connected
     * @param status worker status
     * @param heartbeatAgeMs time since the last heartbeat, or {@code null}
     * @param heartbeatIntervalMs expected heartbeat interval
     * @param verdict {@code OK}, {@code LATE}, {@code DISCONNECTED} or {@code NEVER_CONNECTED}
     * @param explanation what the test checked and found
     */
    public record ConnectionTest(String workerId, boolean coordinatorListening, boolean connected, String status,
            Long heartbeatAgeMs, long heartbeatIntervalMs, String verdict, String explanation) {
    }
}
