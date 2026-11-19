package org.districompute.core.validation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.algorithm.Parameters;
import org.junit.jupiter.api.Test;

class ParameterValidatorTest {

    private static final List<ParameterSpec> SCHEMA = List.of(
            ParameterSpec.integer("size", "Elements").range(0, 1_000_000).defaultValue(100).build(),
            ParameterSpec.decimal("tolerance", "Tolerance").range(0.0, 1.0).defaultValue(1e-9).build(),
            ParameterSpec.bool("verbose", "Verbose").defaultValue(false).build(),
            ParameterSpec.choice("distribution", "Distribution", List.of("UNIFORM", "SORTED")).defaultValue("UNIFORM")
                    .build(),
            ParameterSpec.integer("seed", "Seed").required().build(),
            ParameterSpec.decimalArray("coefficients", "Coefficients").length(1, 4).range(-10, 10).build());

    @Test
    void appliesDefaultsAndConvertsTypes() {
        Parameters p = ParameterValidator.validate(SCHEMA, Map.of("seed", "42", "verbose", "TRUE",
                "distribution", "sorted", "coefficients", List.of(1, 2.5, "-3")));
        assertEquals(100, p.getLong("size"));
        assertEquals(1e-9, p.getDouble("tolerance"));
        assertTrue(p.getBoolean("verbose"));
        assertEquals("SORTED", p.getString("distribution"));
        assertEquals(42, p.getLong("seed"));
        assertArrayEquals(new double[] {1, 2.5, -3}, p.getDoubleArray("coefficients"));
    }

    @Test
    void acceptsIntegralDecimalsForIntegers() {
        Parameters p = ParameterValidator.validate(SCHEMA, Map.of("seed", 7.0, "size", 1e3));
        assertEquals(7, p.getLong("seed"));
        assertEquals(1000, p.getLong("size"));
    }

    @Test
    void optionalParameterWithoutDefaultStaysAbsent() {
        Parameters p = ParameterValidator.validate(SCHEMA, Map.of("seed", 1));
        assertFalse(p.has("coefficients"));
        assertThrows(IllegalArgumentException.class, () -> p.getDoubleArray("coefficients"));
    }

    @Test
    void reportsEveryViolationAtOnce() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("size", -1);
        raw.put("tolerance", Double.NaN);
        raw.put("verbose", "maybe");
        raw.put("distribution", "GAUSSIAN");
        raw.put("colour", "red");
        raw.put("coefficients", List.of());
        ValidationException e = assertThrows(ValidationException.class,
                () -> ParameterValidator.validate(SCHEMA, ParameterValues.normalizeAll(raw)));
        assertEquals(ErrorCode.VALIDATION_FAILED, e.code());
        Map<String, String> codes = new LinkedHashMap<>();
        e.violations().forEach(v -> codes.put(v.field(), v.code()));
        assertEquals(Map.of(
                "parameters.colour", Violation.Codes.UNKNOWN_PARAMETER,
                "parameters.size", Violation.Codes.BELOW_MINIMUM,
                "parameters.tolerance", Violation.Codes.NOT_FINITE,
                "parameters.verbose", Violation.Codes.TYPE_MISMATCH,
                "parameters.distribution", Violation.Codes.NOT_ALLOWED,
                "parameters.seed", Violation.Codes.REQUIRED,
                "parameters.coefficients", Violation.Codes.TOO_SHORT), codes);
    }

    @Test
    void rejectsNonIntegralAndOverflowingIntegers() {
        assertViolation(Map.of("seed", 1.5), "parameters.seed", Violation.Codes.TYPE_MISMATCH);
        assertViolation(Map.of("seed", true), "parameters.seed", Violation.Codes.TYPE_MISMATCH);
        assertViolation(Map.of("seed", new BigInteger("99999999999999999999")), "parameters.seed",
                Violation.Codes.ABOVE_MAXIMUM);
        assertViolation(Map.of("seed", 1, "size", 1_000_001), "parameters.size", Violation.Codes.ABOVE_MAXIMUM);
    }

    @Test
    void checksArrayElementsAndLength() {
        assertViolation(Map.of("seed", 1, "coefficients", List.of(1, 2, 3, 4, 5)), "parameters.coefficients",
                Violation.Codes.TOO_LONG);
        assertViolation(Map.of("seed", 1, "coefficients", List.of(1, 11)), "parameters.coefficients[1]",
                Violation.Codes.ABOVE_MAXIMUM);
        assertViolation(Map.of("seed", 1, "coefficients", 3), "parameters.coefficients",
                Violation.Codes.TYPE_MISMATCH);
    }

    @Test
    void schemaRejectsInconsistentDefinitions() {
        assertThrows(IllegalArgumentException.class,
                () -> ParameterSpec.integer("n", "x").required().defaultValue(1).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterSpec.integer("n", "x").range(5, 1).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterSpec.integer("n", "x").range(0.5, 1).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterSpec.integer("N", "x").build());
        assertThrows(IllegalArgumentException.class, () -> ParameterSpec.integer("n", " ").build());
        assertThrows(IllegalArgumentException.class,
                () -> ParameterSpec.choice("mode", "x", List.of("A", "A")).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterSpec.bool("flag", "x").range(0, 1).build());
    }

    private static void assertViolation(Map<String, Object> raw, String field, String code) {
        ValidationException e = assertThrows(ValidationException.class,
                () -> ParameterValidator.validate(SCHEMA, ParameterValues.normalizeAll(raw)));
        assertTrue(e.violations().stream().anyMatch(v -> v.field().equals(field) && v.code().equals(code)),
                () -> "expected " + field + "/" + code + " in " + e.violations());
    }
}
