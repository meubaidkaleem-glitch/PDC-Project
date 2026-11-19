package org.districompute.core.algorithm;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Normalizes raw parameter values into the canonical, JSON-compatible model used by task specifications:
 * {@link Long}, {@link Double}, {@link Boolean}, {@link String}, {@link BigInteger} (an out-of-range integer, kept
 * so validation can report it), and unmodifiable {@link List}s of those.
 *
 * <p>Normalizing makes task specifications compare equal before and after a JSON round trip, whether a value was
 * written as {@code Integer}, {@code Long} or a primitive array.
 */
public final class ParameterValues {

    private ParameterValues() {
    }

    /**
     * Normalizes a parameter map, keeping insertion order.
     *
     * @param raw raw parameters; {@code null} is treated as empty
     * @return an unmodifiable normalized copy
     * @throws IllegalArgumentException if a name is blank or a value has an unsupported type or is {@code null}
     */
    public static Map<String, Object> normalizeAll(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : raw.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Parameter names must not be blank");
            }
            normalized.put(name, normalize(name, entry.getValue()));
        }
        return Collections.unmodifiableMap(normalized);
    }

    /**
     * Normalizes a single value.
     *
     * @param name parameter name, used in error messages
     * @param value raw value
     * @return the normalized value
     * @throws IllegalArgumentException if the value is {@code null} or has an unsupported type
     */
    public static Object normalize(String name, Object value) {
        Objects.requireNonNull(name, "name");
        if (value == null) {
            throw new IllegalArgumentException("Parameter '" + name + "' must not be null; omit it instead");
        }
        if (value instanceof Long || value instanceof Double || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof BigInteger big) {
            return big.bitLength() < Long.SIZE ? (Object) big.longValue() : big;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Collection<?> collection) {
            List<Object> list = new ArrayList<>(collection.size());
            for (Object element : collection) {
                list.add(normalize(name, element));
            }
            return Collections.unmodifiableList(list);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> list = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                list.add(normalize(name, Array.get(value, i)));
            }
            return Collections.unmodifiableList(list);
        }
        throw new IllegalArgumentException("Parameter '" + name + "' has unsupported value type "
                + value.getClass().getName());
    }
}
