package org.districompute.core.validation;

import java.util.Map;
import java.util.Objects;
import org.districompute.core.algorithm.ParameterValues;

/**
 * Outcome of verifying a final result independently of the computation that produced it.
 *
 * @param method name of the verification method, for example {@code "Freivalds (3 rounds)"}
 * @param kind kind of evidence provided
 * @param passed whether the result passed
 * @param message human-readable summary
 * @param details method-specific values (for example tolerance and maximum observed error), JSON-compatible
 */
public record VerificationReport(
        String method, VerificationKind kind, boolean passed, String message, Map<String, Object> details) {

    /** Validates and normalizes the components. */
    public VerificationReport {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(message, "message");
        details = ParameterValues.normalizeAll(details);
    }

    /**
     * Creates a passing report.
     *
     * @param method method name
     * @param kind kind of evidence
     * @param message summary
     * @param details method-specific values
     * @return the report
     */
    public static VerificationReport passed(
            String method, VerificationKind kind, String message, Map<String, ?> details) {
        return new VerificationReport(method, kind, true, message, ParameterValues.normalizeAll(details));
    }

    /**
     * Creates a failing report.
     *
     * @param method method name
     * @param kind kind of evidence
     * @param message what was wrong
     * @param details method-specific values
     * @return the report
     */
    public static VerificationReport failed(
            String method, VerificationKind kind, String message, Map<String, ?> details) {
        return new VerificationReport(method, kind, false, message, ParameterValues.normalizeAll(details));
    }
}
