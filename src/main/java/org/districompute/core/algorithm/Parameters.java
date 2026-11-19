package org.districompute.core.algorithm;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated, typed parameter values handed to an algorithm. Instances are created by {@code ParameterValidator}
 * after schema validation, so the getters never see a value of the wrong type. Asking for a name that is not in the
 * algorithm's schema is a programming error and throws {@link IllegalArgumentException}.
 *
 * <p>Stored representations: {@code Long}, {@code Double}, {@code Boolean}, {@code String}, {@code long[]} and
 * {@code double[]}. Array getters return copies.
 */
public final class Parameters {

    private final Map<String, Object> values;

    private Parameters(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * Wraps already-validated values. Intended for {@code ParameterValidator} and tests.
     *
     * @param validated values whose types match their schemas
     * @return the parameters
     */
    public static Parameters ofValidated(Map<String, Object> validated) {
        Map<String, Object> copy = new LinkedHashMap<>();
        validated.forEach((name, value) -> copy.put(Objects.requireNonNull(name), copyIfArray(value)));
        return new Parameters(Collections.unmodifiableMap(copy));
    }

    /**
     * Whether a value (supplied or default) is present.
     *
     * @param name parameter name
     * @return {@code true} if present
     */
    public boolean has(String name) {
        return values.containsKey(name);
    }

    /**
     * Returns an integer parameter.
     *
     * @param name parameter name
     * @return the value
     */
    public long getLong(String name) {
        return get(name, Long.class);
    }

    /**
     * Returns an integer parameter that the schema bounds to the {@code int} range.
     *
     * @param name parameter name
     * @return the value
     */
    public int getInt(String name) {
        long value = getLong(name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalStateException("Parameter '" + name + "' = " + value + " does not fit in an int; "
                    + "its schema must bound it");
        }
        return (int) value;
    }

    /**
     * Returns a decimal parameter.
     *
     * @param name parameter name
     * @return the value
     */
    public double getDouble(String name) {
        return get(name, Double.class);
    }

    /**
     * Returns a boolean parameter.
     *
     * @param name parameter name
     * @return the value
     */
    public boolean getBoolean(String name) {
        return get(name, Boolean.class);
    }

    /**
     * Returns an enumerated parameter as its string value.
     *
     * @param name parameter name
     * @return the value
     */
    public String getString(String name) {
        return get(name, String.class);
    }

    /**
     * Returns an enumerated parameter as a Java enum constant with the same name.
     *
     * @param name parameter name
     * @param type enum class
     * @param <E> enum type
     * @return the constant
     */
    public <E extends Enum<E>> E getEnum(String name, Class<E> type) {
        return Enum.valueOf(type, getString(name));
    }

    /**
     * Returns a copy of an integer-array parameter.
     *
     * @param name parameter name
     * @return the values
     */
    public long[] getLongArray(String name) {
        return get(name, long[].class).clone();
    }

    /**
     * Returns a copy of a decimal-array parameter.
     *
     * @param name parameter name
     * @return the values
     */
    public double[] getDoubleArray(String name) {
        return get(name, double[].class).clone();
    }

    /**
     * Returns the names of all present parameters, in schema order.
     *
     * @return parameter names
     */
    public List<String> names() {
        return List.copyOf(values.keySet());
    }

    private <T> T get(String name, Class<T> type) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Parameter '" + name + "' is not present; check the algorithm schema");
        }
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Parameter '" + name + "' is a " + value.getClass().getSimpleName()
                    + ", not a " + type.getSimpleName());
        }
        return type.cast(value);
    }

    private static Object copyIfArray(Object value) {
        if (value instanceof long[] longs) {
            return longs.clone();
        }
        if (value instanceof double[] doubles) {
            return doubles.clone();
        }
        return value;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("Parameters{");
        values.forEach((name, value) -> {
            text.append(name).append('=');
            if (value instanceof long[] longs) {
                text.append("long[").append(longs.length).append(']');
            } else if (value instanceof double[] doubles) {
                text.append("double[").append(doubles.length).append(']');
            } else {
                text.append(value);
            }
            text.append(", ");
        });
        if (!values.isEmpty()) {
            text.setLength(text.length() - 2);
        }
        return text.append('}').toString();
    }
}
