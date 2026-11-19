package org.districompute.core.algorithm;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Permanent identifier of an algorithm, written as {@code family.operation} (for example {@code matrix.multiply}).
 * Identifiers are never reused for a different computation. In JSON it is a plain string.
 *
 * @param value the identifier text
 */
public record AlgorithmId(@JsonValue String value) implements Comparable<AlgorithmId> {

    private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z][a-z0-9-]*)+$");

    /** Validates the identifier format. */
    public AlgorithmId {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid algorithm id '" + value
                    + "': expected lowercase 'family.operation', e.g. 'matrix.multiply'");
        }
    }

    /**
     * Parses an identifier.
     *
     * @param value identifier text
     * @return the identifier
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static AlgorithmId of(String value) {
        return new AlgorithmId(value);
    }

    @Override
    public int compareTo(AlgorithmId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
