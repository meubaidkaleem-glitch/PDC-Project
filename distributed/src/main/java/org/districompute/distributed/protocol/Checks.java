package org.districompute.distributed.protocol;

import java.util.List;
import java.util.Objects;

/** Validation helpers for payload records. Violations become {@code INVALID_PAYLOAD} errors. */
final class Checks {

    private Checks() {
    }

    static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must have 1-" + maxLength + " characters");
        }
        return value;
    }

    static String optionalText(String value, String name, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(name + " must have at most " + maxLength + " characters");
        }
        return value;
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength - 1) + "…";
    }

    static <T> List<T> boundedCopy(List<T> values, String name, int maxSize) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > maxSize) {
            throw new IllegalArgumentException(name + " must have at most " + maxSize + " entries");
        }
        return List.copyOf(values);
    }

    static long nonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
