package org.districompute.app.task;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * A task as the user defines it: an algorithm, its parameters, uploaded inputs and execution settings. It becomes a
 * Core {@code TaskSpec} per run ({@link TaskFactory}).
 *
 * @param algorithm Core algorithm id
 * @param parameters Core parameters; inline arrays at most {@link TaskFactory#MAX_INLINE_ELEMENTS} elements
 * @param inputs uploaded inputs: Core array parameter name to upload id ({@code POST /api/v1/inputs})
 * @param execution execution settings
 */
public record TaskDraft(
        @NotBlank @Size(max = 64) String algorithm,
        Map<String, Object> parameters,
        Map<String, String> inputs,
        @NotNull @Valid ExecutionSettings execution) {

    /** Normalizes absent maps to empty ones. */
    public TaskDraft {
        parameters = parameters == null ? Map.of() : parameters;
        inputs = inputs == null ? Map.of() : inputs;
    }
}
