package org.districompute.app.monitoring;

import java.time.Clock;
import java.util.Map;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.Enums.Severity;
import org.districompute.app.persistence.ExecutionEvent;
import org.springframework.stereotype.Component;

/** Publishes events of the application itself (submissions, finished runs and experiments). */
@Component
public class ApplicationEvents {

    private final EventRecorder recorder;
    private final ExperimentDirectory directory;
    private final JsonSupport json;
    private final Clock clock;

    ApplicationEvents(EventRecorder recorder, ExperimentDirectory directory, JsonSupport json, Clock clock) {
        this.recorder = recorder;
        this.directory = directory;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Publishes an event: stored, then streamed.
     *
     * @param experimentId experiment, or {@code null}
     * @param runId run, or {@code null}
     * @param type event type
     * @param severity severity
     * @param message message
     * @param details details
     */
    public void publish(String experimentId, Long runId, String type, Severity severity, String message,
            Map<String, Object> details) {
        ExecutionEvent event = new ExecutionEvent(experimentId, runId, null, null, null, type, severity, message,
                json.write(details), false, clock.instant());
        recorder.enqueue(new EventRecorder.Pending(event, details, directory.owner(experimentId).orElse(null)));
    }
}
