package org.districompute.app.template;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.districompute.app.task.TaskDraft;

/** API records of experiment templates ({@code docs/batches-and-templates.md}). */
public final class TemplateViews {

    private TemplateViews() {
    }

    /**
     * A template to save.
     *
     * @param name unique among the user's templates
     * @param description description
     * @param task the task: algorithm, parameters, uploaded inputs and execution settings
     */
    public record SaveTemplate(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotNull @Valid TaskDraft task) {
    }

    /**
     * A template made from an existing experiment.
     *
     * @param name unique among the user's templates
     * @param description description
     */
    public record TemplateFromExperiment(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description) {
    }

    /**
     * A template.
     *
     * @param id identifier
     * @param name name
     * @param description description
     * @param algorithm algorithm id
     * @param executionMode execution mode
     * @param task the stored task, ready to analyse or submit
     * @param createdAt creation time
     */
    public record TemplateView(Long id, String name, String description, String algorithm, String executionMode,
            TaskDraft task, Instant createdAt) {
    }
}
