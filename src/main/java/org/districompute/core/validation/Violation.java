package org.districompute.core.validation;

import java.util.Objects;

/**
 * One field-level validation problem.
 *
 * @param field path of the offending field, for example {@code parameters.rowsA}
 * @param code machine-readable violation code, for example {@code ABOVE_MAXIMUM}
 * @param message human-readable explanation
 */
public record Violation(String field, String code, String message) {

    /** Standard violation codes used by Core. Algorithms may add their own descriptive codes. */
    public static final class Codes {
        /** A required value is missing. */
        public static final String REQUIRED = "REQUIRED";
        /** A parameter is not defined by the algorithm's schema. */
        public static final String UNKNOWN_PARAMETER = "UNKNOWN_PARAMETER";
        /** A value has the wrong type. */
        public static final String TYPE_MISMATCH = "TYPE_MISMATCH";
        /** A numeric value is below its minimum. */
        public static final String BELOW_MINIMUM = "BELOW_MINIMUM";
        /** A numeric value is above its maximum. */
        public static final String ABOVE_MAXIMUM = "ABOVE_MAXIMUM";
        /** A decimal value is NaN or infinite. */
        public static final String NOT_FINITE = "NOT_FINITE";
        /** An enumerated value is not allowed. */
        public static final String NOT_ALLOWED = "NOT_ALLOWED";
        /** An array is shorter than permitted. */
        public static final String TOO_SHORT = "TOO_SHORT";
        /** An array is longer than permitted. */
        public static final String TOO_LONG = "TOO_LONG";
        /** Related values are inconsistent (for example a lower bound above an upper bound). */
        public static final String INCONSISTENT = "INCONSISTENT";
        /** Matrix or array dimensions do not match. */
        public static final String DIMENSION_MISMATCH = "DIMENSION_MISMATCH";
        /** The requested problem is too large for the supported representation. */
        public static final String TOO_LARGE = "TOO_LARGE";
        /** A document could not be parsed or bound. */
        public static final String MALFORMED_DOCUMENT = "MALFORMED_DOCUMENT";

        private Codes() {
        }
    }

    /** Validates the components. */
    public Violation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Creates a violation for an algorithm parameter.
     *
     * @param parameter parameter name
     * @param code violation code
     * @param message explanation
     * @return the violation, with field {@code parameters.<parameter>}
     */
    public static Violation ofParameter(String parameter, String code, String message) {
        return new Violation("parameters." + parameter, code, message);
    }
}
