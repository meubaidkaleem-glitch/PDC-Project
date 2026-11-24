package org.districompute.app.task;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts JSON-decoded parameter values into the forms Core's validator accepts ({@code Long}, {@code Double},
 * {@code BigInteger}, {@code String}, {@code Boolean}, lists of those). Core rejects {@code Integer} and
 * {@code BigDecimal}, which JSON decoders produce.
 */
public final class ParameterValues {

    private ParameterValues() {
    }

    /**
     * Normalizes a parameter map.
     *
     * @param raw decoded values
     * @return a new map with normalized values, in the same order
     */
    public static Map<String, Object> normalize(Map<String, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (value != null) {
                out.put(key, value(value));
            }
        });
        return out;
    }

    static Object value(Object value) {
        if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float f) {
            return f.doubleValue();
        }
        if (value instanceof BigDecimal big) {
            try {
                return big.longValueExact();
            } catch (ArithmeticException notIntegral) {
                return big.doubleValue();
            }
        }
        if (value instanceof BigInteger big) {
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(element == null ? null : value(element));
            }
            return copy;
        }
        return value;
    }

    /**
     * Counts the elements of every array value.
     *
     * @param parameters parameters
     * @return the largest array length (0 if none)
     */
    public static int largestArray(Map<String, ?> parameters) {
        return parameters.values().stream().filter(v -> v instanceof List<?>).mapToInt(v -> ((List<?>) v).size())
                .max().orElse(0);
    }
}
