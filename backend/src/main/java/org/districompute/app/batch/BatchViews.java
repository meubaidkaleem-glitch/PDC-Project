package org.districompute.app.batch;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.districompute.app.experiment.ExperimentViews.ExperimentSummary;
import org.districompute.app.task.TaskDraft;

/** API records of batches ({@code docs/batches-and-templates.md}). */
public final class BatchViews {

    private BatchViews() {
    }

    /**
     * One experiment of a new batch.
     *
     * @param name experiment name
     * @param description description
     * @param task the task
     * @param overrideReason why the recommendation is not followed, if it is not
     */
    public record BatchItem(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotNull @Valid TaskDraft task, @Size(max = 500) String overrideReason) {
    }

    /**
     * A new batch.
     *
     * @param name batch name
     * @param items its experiments, run in this order
     */
    public record CreateBatch(@NotBlank @Size(max = 200) String name,
            @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid BatchItem> items) {
    }

    /**
     * A batch.
     *
     * @param id identifier
     * @param name name
     * @param owner owner
     * @param createdAt creation time
     * @param status QUEUED, RUNNING, COMPLETED, FAILED (some experiment failed) or CANCELLED (some cancelled, none
     *     failed), derived from its experiments
     * @param counts experiments per status
     * @param experiments its experiments in order
     */
    public record BatchView(String id, String name, String owner, Instant createdAt, String status,
            Map<String, Integer> counts, List<ExperimentSummary> experiments) {
    }
}
