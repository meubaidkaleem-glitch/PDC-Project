package org.districompute.core.algorithm.matrix;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.validation.Violation;

/**
 * Input handling shared by the binary matrix algorithms. Operands are either generated deterministically
 * ({@code input = GENERATED}: {@code distribution}, {@code seed}, stream 1 for A and stream 2 for B) or supplied
 * inline in row-major order ({@code input = INLINE}: {@code matrixA}, {@code matrixB}).
 */
public final class MatrixInputs {

    /** Largest inline matrix, in elements. */
    public static final int MAX_INLINE_ELEMENTS = 1_000_000;

    private MatrixInputs() {
    }

    /**
     * Parameters that describe where the operands come from.
     *
     * @return parameter schemas for input, distribution, seed, matrixA and matrixB
     */
    public static List<ParameterSpec> sourceParameters() {
        return List.of(
                ParameterSpec.choice("input", "Where the operands come from", List.of("GENERATED", "INLINE"))
                        .defaultValue("GENERATED").build(),
                ParameterSpec.choice("distribution", "Element distribution of generated operands",
                        Arrays.stream(ValueDistribution.values()).map(Enum::name).toList())
                        .defaultValue("UNIFORM").build(),
                ParameterSpec.integer("seed", "Generator seed for generated operands")
                        .range(0, Long.MAX_VALUE).defaultValue(42).build(),
                ParameterSpec.decimalArray("matrixA", "Left operand in row-major order (input = INLINE)")
                        .length(1, MAX_INLINE_ELEMENTS).build(),
                ParameterSpec.decimalArray("matrixB", "Right operand in row-major order (input = INLINE)")
                        .length(1, MAX_INLINE_ELEMENTS).build());
    }

    /**
     * Whether the operands are supplied inline.
     *
     * @param parameters validated parameters
     * @return {@code true} for {@code input = INLINE}
     */
    public static boolean isInline(Parameters parameters) {
        return "INLINE".equals(parameters.getString("input"));
    }

    /**
     * Checks that the operands' element counts fit in arrays and that inline operands are present exactly when
     * required, with lengths matching their declared shapes.
     *
     * @param parameters validated parameters
     * @param rowsA rows of A
     * @param colsA columns of A
     * @param rowsB rows of B
     * @param colsB columns of B
     * @return violations found
     */
    public static List<Violation> validate(Parameters parameters, long rowsA, long colsA, long rowsB, long colsB) {
        List<Violation> violations = new ArrayList<>();
        requireFits(violations, "matrixA", rowsA * colsA, rowsA + "x" + colsA);
        requireFits(violations, "matrixB", rowsB * colsB, rowsB + "x" + colsB);
        if (isInline(parameters)) {
            checkInline(violations, parameters, "matrixA", rowsA, colsA);
            checkInline(violations, parameters, "matrixB", rowsB, colsB);
        } else {
            for (String name : List.of("matrixA", "matrixB")) {
                if (parameters.has(name)) {
                    violations.add(Violation.ofParameter(name, Violation.Codes.INCONSISTENT,
                            "is only allowed when input = INLINE"));
                }
            }
        }
        return violations;
    }

    /**
     * Adds a {@code TOO_LARGE} violation if a matrix cannot be held in one array.
     *
     * @param violations collector
     * @param field parameter to blame
     * @param elements element count
     * @param shape shape text for the message
     */
    public static void requireFits(List<Violation> violations, String field, long elements, String shape) {
        if (!Sizes.fitsInArray(elements)) {
            violations.add(Violation.ofParameter(field, Violation.Codes.TOO_LARGE, "a " + shape + " matrix has "
                    + elements + " elements, above the array limit " + Sizes.MAX_ARRAY_LENGTH));
        }
    }

    /**
     * Materializes the operands.
     *
     * @param parameters validated parameters
     * @param rowsA rows of A
     * @param colsA columns of A
     * @param rowsB rows of B
     * @param colsB columns of B
     * @param cancellation cancellation token for generation
     * @return the operands
     */
    public static MatrixPair prepare(Parameters parameters, int rowsA, int colsA, int rowsB, int colsB,
            CancellationToken cancellation) {
        if (isInline(parameters)) {
            return new MatrixPair(new Matrix(rowsA, colsA, parameters.getDoubleArray("matrixA")),
                    new Matrix(rowsB, colsB, parameters.getDoubleArray("matrixB")));
        }
        ValueDistribution distribution = parameters.getEnum("distribution", ValueDistribution.class);
        long seed = parameters.getLong("seed");
        return new MatrixPair(distribution.generate(rowsA, colsA, seed, 1, cancellation),
                distribution.generate(rowsB, colsB, seed, 2, cancellation));
    }

    private static void checkInline(List<Violation> violations, Parameters parameters, String name, long rows,
            long cols) {
        if (!parameters.has(name)) {
            violations.add(Violation.ofParameter(name, Violation.Codes.REQUIRED, "is required when input = INLINE"));
            return;
        }
        int length = parameters.getDoubleArray(name).length;
        if (length != rows * cols) {
            violations.add(Violation.ofParameter(name, Violation.Codes.DIMENSION_MISMATCH,
                    "has " + length + " elements but a " + rows + "x" + cols + " matrix needs " + rows * cols));
        }
    }
}
