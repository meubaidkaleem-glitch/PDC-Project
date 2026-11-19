package org.districompute.core.algorithm;

/** Value type of an algorithm parameter. */
public enum ParameterType {
    /** 64-bit signed integer. */
    INTEGER,
    /** Finite IEEE-754 double. */
    DECIMAL,
    /** {@code true} or {@code false}. */
    BOOLEAN,
    /** One of a fixed set of string values. */
    ENUM,
    /** Array of 64-bit signed integers (small inline inputs). */
    INTEGER_ARRAY,
    /** Array of finite doubles (small inline inputs). */
    DECIMAL_ARRAY;

    /**
     * Whether values are arrays.
     *
     * @return {@code true} for the array types
     */
    public boolean isArray() {
        return this == INTEGER_ARRAY || this == DECIMAL_ARRAY;
    }

    /**
     * Whether values (or array elements) are integers.
     *
     * @return {@code true} for {@link #INTEGER} and {@link #INTEGER_ARRAY}
     */
    public boolean isIntegral() {
        return this == INTEGER || this == INTEGER_ARRAY;
    }

    /**
     * Whether values (or array elements) are numbers.
     *
     * @return {@code true} for the integer and decimal types
     */
    public boolean isNumeric() {
        return this != BOOLEAN && this != ENUM;
    }
}
