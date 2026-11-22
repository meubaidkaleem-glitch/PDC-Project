package org.districompute.distributed.metrics;

import java.time.Instant;
import java.util.Map;

/**
 * Coordinator-wide figures for monitoring dashboards ({@code GET /api/v1/metrics}).
 *
 * @param coordinatorId the coordinator
 * @param startedAt when it started
 * @param uptimeMillis time since start
 * @param workersByStatus number of known workers per status
 * @param totalSlots slots of connected workers that may receive attempts (active)
 * @param busySlots slots occupied by assigned attempts, on connected workers
 * @param activeTasks tasks not yet ended
 * @param pendingPartitions partitions waiting for a worker
 * @param tasksSubmitted tasks submitted since start
 * @param tasksCompleted tasks that completed
 * @param tasksFailed tasks that failed
 * @param tasksCancelled tasks that were cancelled
 * @param attemptsDispatched attempts sent to workers
 * @param attemptsSucceeded attempts whose result Core accepted
 * @param attemptsFailed attempts that failed, were refused, timed out, were lost or returned invalid results
 * @param partitionsRetried partitions queued again after a failure
 * @param bytesSent bytes of {@code TASK} frames
 * @param bytesReceived bytes of {@code RESULT} frames
 * @param lastEventSequence sequence number of the latest event
 */
public record CoordinatorMetrics(String coordinatorId, Instant startedAt, long uptimeMillis,
        Map<String, Integer> workersByStatus, int totalSlots, int busySlots, int activeTasks, int pendingPartitions,
        long tasksSubmitted, long tasksCompleted, long tasksFailed, long tasksCancelled, long attemptsDispatched,
        long attemptsSucceeded, long attemptsFailed, long partitionsRetried, long bytesSent, long bytesReceived,
        long lastEventSequence) {

    /** Copies the map. */
    public CoordinatorMetrics {
        workersByStatus = Map.copyOf(workersByStatus);
    }
}
