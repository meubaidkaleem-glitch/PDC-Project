package org.districompute.app.task;

import java.util.LinkedHashMap;
import java.util.Map;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.Experiment;
import org.springframework.stereotype.Component;

/**
 * Rebuilds the task an experiment was created with from what is stored: parameters (uploaded inputs are stored as
 * {@code {"upload": "<id>"}} references) and execution settings. Used to start each run, to repeat or copy an
 * experiment, and to save it as a template.
 */
@Component
public class StoredTasks {

    private final JsonSupport json;

    StoredTasks(JsonSupport json) {
        this.json = json;
    }

    /**
     * The task of an experiment.
     *
     * @param experiment the experiment
     * @return its task, with uploaded inputs by reference
     */
    public TaskDraft draftOf(Experiment experiment) {
        Map<String, Object> parameters = new LinkedHashMap<>(json.map(experiment.getParameters()));
        Map<String, String> uploads = new LinkedHashMap<>();
        parameters.entrySet().removeIf(e -> {
            if (e.getValue() instanceof Map<?, ?> ref && ref.get("upload") instanceof String id) {
                uploads.put(e.getKey(), id);
                return true;
            }
            return false;
        });
        ExecutionSettings settings = json.read(experiment.getConfiguration(), ExecutionSettings.class);
        return new TaskDraft(experiment.getAlgorithmId(), parameters, uploads, settings);
    }
}
