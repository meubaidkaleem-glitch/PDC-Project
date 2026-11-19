package org.districompute.core.algorithm;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Schema of one algorithm parameter. The catalog publishes these schemas so the application can render input forms,
 * and {@code ParameterValidator} enforces them.
 *
 * <p>A parameter is either required (no default allowed) or optional. An optional parameter has a default value or
 * is simply absent. For array types, {@code minimum} and {@code maximum} constrain each element and
 * {@code minLength} and {@code maxLength} constrain the length.
 *
 * @param name parameter name in {@code lowerCamelCase}
 * @param type value type
 * @param description human-readable description
 * @param required whether the caller must supply a value
 * @param defaultValue value used when omitted, or {@code null}
 * @param minimum inclusive lower bound for numeric values or elements, or {@code null}
 * @param maximum inclusive upper bound for numeric values or elements, or {@code null}
 * @param allowedValues permitted values of an {@link ParameterType#ENUM}; empty for other types
 * @param minLength minimum array length, or {@code null}
 * @param maxLength maximum array length, or {@code null}
 * @param unit unit of measure shown to users (for example {@code "rows"}), or {@code null}
 */
public record ParameterSpec(
        String name,
        ParameterType type,
        String description,
        boolean required,
        Object defaultValue,
        Number minimum,
        Number maximum,
        List<String> allowedValues,
        Integer minLength,
        Integer maxLength,
        String unit) {

    private static final Pattern NAME_FORMAT = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    /** Validates the schema for internal consistency and normalizes numeric bounds. */
    public ParameterSpec {
        Objects.requireNonNull(name, "name");
        if (!NAME_FORMAT.matcher(name).matches()) {
            throw new IllegalArgumentException("Parameter name '" + name + "' must be lowerCamelCase");
        }
        Objects.requireNonNull(type, "type");
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Parameter '" + name + "' needs a description");
        }
        if (required && defaultValue != null) {
            throw new IllegalArgumentException("Required parameter '" + name + "' must not have a default");
        }
        defaultValue = defaultValue == null ? null : ParameterValues.normalize(name, defaultValue);
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
        if (type == ParameterType.ENUM) {
            if (allowedValues.isEmpty() || new HashSet<>(allowedValues).size() != allowedValues.size()) {
                throw new IllegalArgumentException("ENUM parameter '" + name + "' needs distinct allowed values");
            }
        } else if (!allowedValues.isEmpty()) {
            throw new IllegalArgumentException("Only ENUM parameters may list allowed values ('" + name + "')");
        }
        minimum = normalizeBound(name, type, minimum);
        maximum = normalizeBound(name, type, maximum);
        if (minimum != null && maximum != null && minimum.doubleValue() > maximum.doubleValue()) {
            throw new IllegalArgumentException("Parameter '" + name + "': minimum exceeds maximum");
        }
        if (!type.isArray() && (minLength != null || maxLength != null)) {
            throw new IllegalArgumentException("Only array parameters may constrain length ('" + name + "')");
        }
        if ((minLength != null && minLength < 0) || (maxLength != null && maxLength < 0)
                || (minLength != null && maxLength != null && minLength > maxLength)) {
            throw new IllegalArgumentException("Parameter '" + name + "': invalid length bounds");
        }
    }

    private static Number normalizeBound(String name, ParameterType type, Number bound) {
        if (bound == null) {
            return null;
        }
        if (!type.isNumeric()) {
            throw new IllegalArgumentException("Only numeric parameters may have bounds ('" + name + "')");
        }
        if (type.isIntegral()) {
            double asDouble = bound.doubleValue();
            if (asDouble != Math.rint(asDouble)) {
                throw new IllegalArgumentException("Integer parameter '" + name + "' needs integral bounds");
            }
            return bound.longValue();
        }
        double value = bound.doubleValue();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Parameter '" + name + "' needs finite bounds");
        }
        return value;
    }

    /**
     * Starts an integer parameter.
     *
     * @param name parameter name
     * @param description description
     * @return a builder
     */
    public static Builder integer(String name, String description) {
        return new Builder(name, ParameterType.INTEGER, description);
    }

    /**
     * Starts a decimal parameter.
     *
     * @param name parameter name
     * @param description description
     * @return a builder
     */
    public static Builder decimal(String name, String description) {
        return new Builder(name, ParameterType.DECIMAL, description);
    }

    /**
     * Starts a boolean parameter.
     *
     * @param name parameter name
     * @param description description
     * @return a builder
     */
    public static Builder bool(String name, String description) {
        return new Builder(name, ParameterType.BOOLEAN, description);
    }

    /**
     * Starts an enumerated parameter.
     *
     * @param name parameter name
     * @param description description
     * @param values allowed values
     * @return a builder
     */
    public static Builder choice(String name, String description, List<String> values) {
        return new Builder(name, ParameterType.ENUM, description).allowedValues(values);
    }

    /**
     * Starts an integer-array parameter.
     *
     * @param name parameter name
     * @param description description
     * @return a builder
     */
    public static Builder integerArray(String name, String description) {
        return new Builder(name, ParameterType.INTEGER_ARRAY, description);
    }

    /**
     * Starts a decimal-array parameter.
     *
     * @param name parameter name
     * @param description description
     * @return a builder
     */
    public static Builder decimalArray(String name, String description) {
        return new Builder(name, ParameterType.DECIMAL_ARRAY, description);
    }

    /** Fluent builder for {@link ParameterSpec}. */
    public static final class Builder {
        private final String name;
        private final ParameterType type;
        private final String description;
        private boolean required;
        private Object defaultValue;
        private Number minimum;
        private Number maximum;
        private List<String> allowedValues = List.of();
        private Integer minLength;
        private Integer maxLength;
        private String unit;

        private Builder(String name, ParameterType type, String description) {
            this.name = name;
            this.type = type;
            this.description = description;
        }

        /**
         * Marks the parameter as required.
         *
         * @return this builder
         */
        public Builder required() {
            this.required = true;
            return this;
        }

        /**
         * Sets the default value used when the parameter is omitted.
         *
         * @param value default value
         * @return this builder
         */
        public Builder defaultValue(Object value) {
            this.defaultValue = value;
            return this;
        }

        /**
         * Sets inclusive bounds for the value (or each array element).
         *
         * @param min inclusive minimum
         * @param max inclusive maximum
         * @return this builder
         */
        public Builder range(Number min, Number max) {
            this.minimum = min;
            this.maximum = max;
            return this;
        }

        /**
         * Sets an inclusive lower bound.
         *
         * @param min inclusive minimum
         * @return this builder
         */
        public Builder minimum(Number min) {
            this.minimum = min;
            return this;
        }

        /**
         * Sets array length bounds.
         *
         * @param min minimum length
         * @param max maximum length
         * @return this builder
         */
        public Builder length(int min, int max) {
            this.minLength = min;
            this.maxLength = max;
            return this;
        }

        /**
         * Sets the unit shown to users.
         *
         * @param unitOfMeasure unit
         * @return this builder
         */
        public Builder unit(String unitOfMeasure) {
            this.unit = unitOfMeasure;
            return this;
        }

        private Builder allowedValues(List<String> values) {
            this.allowedValues = values;
            return this;
        }

        /**
         * Builds the schema.
         *
         * @return the parameter schema
         */
        public ParameterSpec build() {
            return new ParameterSpec(name, type, description, required, defaultValue, minimum, maximum,
                    allowedValues, minLength, maxLength, unit);
        }
    }
}
