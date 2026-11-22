package org.districompute.distributed.monitoring;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One runtime event. Events have a global, strictly increasing {@code sequence}, so a consumer can resume from the
 * last sequence it saw. {@code details} holds type-specific values (numbers, strings, nested objects); its keys
 * are documented per type in {@code docs/integration-contract.md}.
 *
 * @param sequence global sequence number, starting at 1
 * @param timestamp when the event was published
 * @param type event type
 * @param workerId worker concerned, or {@code null}
 * @param experimentId experiment of the task, or {@code null}
 * @param taskId task concerned, or {@code null}
 * @param partitionId partition concerned, or {@code null}
 * @param attemptId attempt concerned, or {@code null}
 * @param message short human-readable summary
 * @param details type-specific values
 */
public record RuntimeEvent(long sequence, Instant timestamp, EventType type, String workerId, String experimentId,
        String taskId, String partitionId, String attemptId, String message, Map<String, Object> details) {

    /** Validates and copies the components. */
    public RuntimeEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }
}
