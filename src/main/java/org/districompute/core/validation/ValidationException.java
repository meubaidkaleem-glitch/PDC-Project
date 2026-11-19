package org.districompute.core.validation;

import java.util.List;
import java.util.stream.Collectors;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/** Input validation failure carrying every field-level {@link Violation} found, not just the first. */
public final class ValidationException extends DistriComputeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates a validation failure.
     *
     * @param violations the problems found; must not be empty
     */
    public ValidationException(List<Violation> violations) {
        super(ErrorCode.VALIDATION_FAILED, summarize(violations), null, violations, null);
    }

    /**
     * Creates a validation failure for a single problem.
     *
     * @param violation the problem found
     */
    public ValidationException(Violation violation) {
        this(List.of(violation));
    }

    private static String summarize(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            throw new IllegalArgumentException("A validation failure needs at least one violation");
        }
        return violations.stream()
                .map(v -> v.field() + ": " + v.message())
                .collect(Collectors.joining("; ", "Validation failed: ", ""));
    }
}
