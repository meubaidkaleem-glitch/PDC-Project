package org.districompute.app.monitoring;

import java.time.Instant;
import java.util.Map;
import org.districompute.app.persistence.ExecutionEvent;

/**
 * An event as the API and the live streams show it.
 *
 * @param id database id ({@code null} for transient progress events, which are not stored)
 * @param experimentId experiment, or {@code null} for cluster events
 * @param runId run, or {@code null}
 * @param taskRef partition or attempt id, or {@code null}
 * @param workerId worker, or {@code null}
 * @param source {@code RUNTIME} (Distributed coordinator) or {@code APPLICATION}
 * @param type event type
 * @param severity {@code INFO}, {@code WARN} or {@code ERROR}
 * @param message message
 * @param details structured details
 * @param simulated whether the event comes from the fault-simulation lab
 * @param occurredAt time
 */
public record EventView(Long id, String experimentId, Long runId, String taskRef, String workerId, String source,
        String type, String severity, String message, Map<String, Object> details, boolean simulated,
        Instant occurredAt) {

    /**
     * Maps a stored event.
     *
     * @param e the entity
     * @param details parsed details
     * @return the view
     */
    public static EventView of(ExecutionEvent e, Map<String, Object> details) {
        return new EventView(e.getId(), e.getExperimentId(), e.getRunId(), e.getTaskRef(), e.getWorkerId(),
                e.getRuntimeSequence() == null ? "APPLICATION" : "RUNTIME", e.getEventType(), e.getSeverity().name(),
                e.getMessage(), details, e.isSimulated(), e.getOccurredAt());
    }
}
