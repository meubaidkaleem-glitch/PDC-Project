package org.districompute.core.result;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.districompute.core.algorithm.ParameterValues;

/**
 * Compact, serializable description of a result. Large results (matrices, sorted arrays) are summarized. The full
 * value stays available to in-process callers.
 *
 * @param type result type, matching the algorithm descriptor's {@code resultType}
 * @param digest {@code sha256:<hex>} of the result's canonical binary encoding; equal digests mean bitwise-equal
 *     results
 * @param values algorithm-specific summary values (for example a count, dimensions or a few sample elements)
 */
public record ResultSummary(String type, String digest, Map<String, Object> values) {

    private static final Pattern DIGEST_FORMAT = Pattern.compile("^sha256:[0-9a-f]{64}$");

    /** Validates and normalizes the components. */
    public ResultSummary {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(digest, "digest");
        if (!DIGEST_FORMAT.matcher(digest).matches()) {
            throw new IllegalArgumentException("digest must be 'sha256:' followed by 64 lowercase hex digits");
        }
        values = ParameterValues.normalizeAll(values);
    }
}
