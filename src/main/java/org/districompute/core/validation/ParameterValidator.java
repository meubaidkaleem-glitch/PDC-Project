package org.districompute.core.validation;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.ParameterType;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.algorithm.Parameters;

/**
 * Validates raw parameter values against a parameter schema and converts them to typed {@link Parameters}.
 *
 * <p>Every problem is collected, so the caller can report all errors at once. Conversion rules:
 * <ul>
 *   <li>{@code INTEGER}: integral numbers, or decimals with no fractional part that are exactly representable,
 *       or numeric strings. Booleans are rejected.</li>
 *   <li>{@code DECIMAL}: any finite number or numeric string. NaN and infinities are rejected.</li>
 *   <li>{@code BOOLEAN}: booleans, or the strings {@code true} and {@code false} (case-insensitive).</li>
 *   <li>{@code ENUM}: an allowed value, matched case-insensitively and stored in its canonical spelling.</li>
 *   <li>Array types: lists whose elements follow the scalar rules, with length and element bounds checked.</li>
 * </ul>
 */
public final class ParameterValidator {

    private static final double TWO_POW_63 = 0x1p63;

    private ParameterValidator() {
    }

    /**
     * Validates and converts parameters.
     *
     * @param specs the algorithm's parameter schema
     * @param raw raw values, for example from {@code TaskSpec.parameters()}
     * @return typed parameters, including defaults for omitted optional parameters
     * @throws ValidationException listing every violation found
     */
    public static Parameters validate(List<ParameterSpec> specs, Map<String, ?> raw) {
        Map<String, ?> input = raw == null ? Map.of() : raw;
        List<Violation> violations = new ArrayList<>();
        Map<String, ParameterSpec> byName = new HashMap<>();
        for (ParameterSpec spec : specs) {
            byName.put(spec.name(), spec);
        }
        for (String name : input.keySet()) {
            if (!byName.containsKey(name)) {
                violations.add(Violation.ofParameter(name, Violation.Codes.UNKNOWN_PARAMETER,
                        "unknown parameter; expected one of " + byName.keySet().stream().sorted().toList()));
            }
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (ParameterSpec spec : specs) {
            Object value = input.get(spec.name());
            if (value == null) {
                if (spec.required()) {
                    violations.add(Violation.ofParameter(spec.name(), Violation.Codes.REQUIRED, "is required"));
                    continue;
                }
                if (spec.defaultValue() == null) {
                    continue;
                }
                value = spec.defaultValue();
            }
            Object typed = convert(spec, ParameterValues.normalize(spec.name(), value), violations);
            if (typed != null) {
                converted.put(spec.name(), typed);
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
        return Parameters.ofValidated(converted);
    }

    private static Object convert(ParameterSpec spec, Object value, List<Violation> violations) {
        return switch (spec.type()) {
            case INTEGER -> checkedInteger(spec, value, spec.name(), violations);
            case DECIMAL -> checkedDecimal(spec, value, spec.name(), violations);
            case BOOLEAN -> toBoolean(spec.name(), value, violations).orElse(null);
            case ENUM -> toEnum(spec, value, violations);
            case INTEGER_ARRAY, DECIMAL_ARRAY -> toArray(spec, value, violations);
        };
    }

    private static Long checkedInteger(ParameterSpec spec, Object value, String field, List<Violation> violations) {
        if (value instanceof BigInteger big) {
            boolean negative = big.signum() < 0;
            violations.add(Violation.ofParameter(field,
                    negative ? Violation.Codes.BELOW_MINIMUM : Violation.Codes.ABOVE_MAXIMUM,
                    "does not fit in a 64-bit integer: " + big));
            return null;
        }
        Long result = toLong(value);
        if (result == null) {
            violations.add(Violation.ofParameter(field, Violation.Codes.TYPE_MISMATCH,
                    "expected an integer, got " + describe(value)));
            return null;
        }
        return checkBounds(spec, result, field, violations) ? result : null;
    }

    private static Double checkedDecimal(ParameterSpec spec, Object value, String field, List<Violation> violations) {
        Double result = toDouble(value);
        if (result == null) {
            violations.add(Violation.ofParameter(field, Violation.Codes.TYPE_MISMATCH,
                    "expected a number, got " + describe(value)));
            return null;
        }
        if (!Double.isFinite(result)) {
            violations.add(Violation.ofParameter(field, Violation.Codes.NOT_FINITE, "must be a finite number"));
            return null;
        }
        return checkBounds(spec, result, field, violations) ? result : null;
    }

    private static boolean checkBounds(ParameterSpec spec, Number value, String field, List<Violation> violations) {
        boolean integral = value instanceof Long;
        if (spec.minimum() != null && compare(value, spec.minimum(), integral) < 0) {
            violations.add(Violation.ofParameter(field, Violation.Codes.BELOW_MINIMUM,
                    "must be at least " + spec.minimum() + ", got " + value));
            return false;
        }
        if (spec.maximum() != null && compare(value, spec.maximum(), integral) > 0) {
            violations.add(Violation.ofParameter(field, Violation.Codes.ABOVE_MAXIMUM,
                    "must be at most " + spec.maximum() + ", got " + value));
            return false;
        }
        return true;
    }

    private static int compare(Number value, Number bound, boolean integral) {
        return integral ? Long.compare(value.longValue(), bound.longValue())
                : Double.compare(value.doubleValue(), bound.doubleValue());
    }

    private static Long toLong(Object value) {
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Double d) {
            boolean integral = Double.isFinite(d) && d == Math.rint(d) && d >= -TWO_POW_63 && d < TWO_POW_63;
            return integral ? (Long) d.longValue() : null;
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Double toDouble(Object value) {
        if (value instanceof Long l) {
            return l.doubleValue();
        }
        if (value instanceof Double d) {
            return d;
        }
        if (value instanceof BigInteger big) {
            return big.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Optional<Boolean> toBoolean(String name, Object value, List<Violation> violations) {
        if (value instanceof Boolean b) {
            return Optional.of(b);
        }
        if (value instanceof String s) {
            String normalized = s.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "false".equals(normalized)) {
                return Optional.of(Boolean.valueOf(normalized));
            }
        }
        violations.add(Violation.ofParameter(name, Violation.Codes.TYPE_MISMATCH,
                "expected true or false, got " + describe(value)));
        return Optional.empty();
    }

    private static String toEnum(ParameterSpec spec, Object value, List<Violation> violations) {
        if (value instanceof String s) {
            for (String allowed : spec.allowedValues()) {
                if (allowed.equalsIgnoreCase(s.trim())) {
                    return allowed;
                }
            }
        }
        violations.add(Violation.ofParameter(spec.name(), Violation.Codes.NOT_ALLOWED,
                "must be one of " + spec.allowedValues() + ", got " + describe(value)));
        return null;
    }

    private static Object toArray(ParameterSpec spec, Object value, List<Violation> violations) {
        if (!(value instanceof List<?> list)) {
            violations.add(Violation.ofParameter(spec.name(), Violation.Codes.TYPE_MISMATCH,
                    "expected an array, got " + describe(value)));
            return null;
        }
        if (spec.minLength() != null && list.size() < spec.minLength()) {
            violations.add(Violation.ofParameter(spec.name(), Violation.Codes.TOO_SHORT,
                    "needs at least " + spec.minLength() + " elements, got " + list.size()));
            return null;
        }
        if (spec.maxLength() != null && list.size() > spec.maxLength()) {
            violations.add(Violation.ofParameter(spec.name(), Violation.Codes.TOO_LONG,
                    "allows at most " + spec.maxLength() + " elements, got " + list.size()));
            return null;
        }
        int before = violations.size();
        boolean integral = spec.type() == ParameterType.INTEGER_ARRAY;
        long[] longs = integral ? new long[list.size()] : null;
        double[] doubles = integral ? null : new double[list.size()];
        for (int i = 0; i < list.size() && violations.size() - before < 5; i++) {
            String field = spec.name() + "[" + i + "]";
            if (integral) {
                Long element = checkedInteger(spec, list.get(i), field, violations);
                longs[i] = element == null ? 0 : element;
            } else {
                Double element = checkedDecimal(spec, list.get(i), field, violations);
                doubles[i] = element == null ? 0 : element;
            }
        }
        if (violations.size() > before) {
            return null;
        }
        return integral ? longs : doubles;
    }

    private static String describe(Object value) {
        String text = String.valueOf(value);
        if (text.length() > 40) {
            text = text.substring(0, 37) + "...";
        }
        return value.getClass().getSimpleName() + " " + text;
    }
}
