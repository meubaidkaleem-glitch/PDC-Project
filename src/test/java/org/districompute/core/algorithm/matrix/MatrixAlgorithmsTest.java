package org.districompute.core.algorithm.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class MatrixAlgorithmsTest {

    private static final ExecutionContext CONTEXT = ExecutionContext.unmanaged();

    /** Trusted reference: the naive triple loop, summing over p in ascending order from 0.0. */
    static Matrix naiveProduct(Matrix a, Matrix b) {
        Matrix c = Matrix.zeros(a.rows(), b.cols());
        for (int i = 0; i < a.rows(); i++) {
            for (int j = 0; j < b.cols(); j++) {
                double sum = 0.0;
                for (int p = 0; p < a.cols(); p++) {
                    sum += a.get(i, p) * b.get(p, j);
                }
                c.data()[i * b.cols() + j] = sum;
            }
        }
        return c;
    }

    private static Matrix random(int rows, int cols, long stream) {
        return ValueDistribution.UNIFORM.generate(rows, cols, 99, stream, CancellationToken.NONE);
    }

    @Test
    void blockedProductIsBitwiseEqualToNaiveForEveryShapeAndBlockSize() {
        int[][] shapes = {{1, 1, 1}, {1, 5, 1}, {7, 3, 5}, {33, 65, 17}, {64, 64, 64}, {100, 1, 100}, {5, 200, 3}};
        for (int[] shape : shapes) {
            Matrix a = random(shape[0], shape[1], 1);
            Matrix b = random(shape[1], shape[2], 2);
            Matrix expected = naiveProduct(a, b);
            for (int block : new int[] {1, 8, 16, 64, 4096}) {
                Matrix actual = MatrixOps.multiply(a, b, block, CancellationToken.NONE, ProgressReporter.NONE);
                assertTrue(expected.equalsExactly(actual), shape[0] + "x" + shape[1] + "x" + shape[2] + " block "
                        + block);
            }
        }
    }

    @Test
    void integerDistributionGivesExactProducts() {
        Matrix a = ValueDistribution.INTEGER.generate(40, 30, 5, 1, CancellationToken.NONE);
        Matrix b = ValueDistribution.INTEGER.generate(30, 20, 5, 2, CancellationToken.NONE);
        Matrix c = MatrixOps.multiply(a, b, 8, CancellationToken.NONE, ProgressReporter.NONE);
        for (int i = 0; i < 40; i++) {
            for (int j = 0; j < 20; j++) {
                long exact = 0;
                for (int p = 0; p < 30; p++) {
                    exact += (long) a.get(i, p) * (long) b.get(p, j);
                }
                assertEquals(exact, c.get(i, j), 0.0);
            }
        }
    }

    @Test
    void rejectsIncompatibleDimensions() {
        ValidationException product = assertThrows(ValidationException.class, () -> MatrixOps.multiply(
                random(2, 3, 1), random(4, 2, 2), 8, CancellationToken.NONE, ProgressReporter.NONE));
        assertEquals(Violation.Codes.DIMENSION_MISMATCH, product.violations().get(0).code());
        ValidationException sum = assertThrows(ValidationException.class, () -> MatrixOps.add(
                random(2, 3, 1), random(3, 2, 2), CancellationToken.NONE, ProgressReporter.NONE));
        assertEquals(Violation.Codes.DIMENSION_MISMATCH, sum.violations().get(0).code());
        assertThrows(IllegalArgumentException.class, () -> new Matrix(2, 2, new double[3]));
        assertThrows(IllegalArgumentException.class, () -> new MatrixMultiplyInput(random(2, 3, 1),
                random(2, 3, 2), 8));
    }

    @Test
    void freivaldsAcceptsCorrectProductsAndCatchesCorruption() {
        Matrix a = random(120, 80, 1);
        Matrix b = random(80, 90, 2);
        Matrix c = MatrixOps.multiply(a, b, 32, CancellationToken.NONE, ProgressReporter.NONE);
        assertTrue(FreivaldsVerifier.verify(a, b, c, 3, 1, CancellationToken.NONE).passed());

        Matrix corrupted = new Matrix(c.rows(), c.cols(), c.data().clone());
        corrupted.data()[57 * 90 + 33] += 1e-6;
        VerificationReport report = FreivaldsVerifier.verify(a, b, corrupted, 3, 1, CancellationToken.NONE);
        assertFalse(report.passed());
        assertEquals(57L, report.details().get("failedRow"));

        Matrix wrongShape = MatrixOps.multiply(a, random(80, 89, 2), 32, CancellationToken.NONE,
                ProgressReporter.NONE);
        assertFalse(FreivaldsVerifier.verify(a, b, wrongShape, 3, 1, CancellationToken.NONE).passed());
    }

    @Test
    void generatedOperandsAreDeterministicPerSeedAndStream() {
        Matrix first = ValueDistribution.UNIFORM.generate(10, 10, 1, 1, CancellationToken.NONE);
        assertTrue(first.equalsExactly(ValueDistribution.UNIFORM.generate(10, 10, 1, 1, CancellationToken.NONE)));
        assertFalse(first.equalsExactly(ValueDistribution.UNIFORM.generate(10, 10, 2, 1, CancellationToken.NONE)));
        assertFalse(first.equalsExactly(ValueDistribution.UNIFORM.generate(10, 10, 1, 2, CancellationToken.NONE)));
        assertEquals(first.digest(), new Matrix(10, 10, first.data().clone()).digest());
        assertNotEquals(first.digest(), new Matrix(1, 100, first.data()).digest(), "shape is part of the digest");
    }

    @Test
    void multiplyAlgorithmValidatesInnerDimensionsOfInlineOperands() {
        MatrixMultiplyAlgorithm algorithm = new MatrixMultiplyAlgorithm();
        Map<String, Object> raw = new HashMap<>(Map.of("input", "INLINE", "rowsA", 2, "colsA", 3, "rowsB", 3,
                "colsB", 2, "matrixA", List.of(1, 2, 3, 4, 5, 6), "matrixB", List.of(7, 8, 9, 10, 11, 12)));
        Parameters valid = validate(algorithm, raw);
        assertTrue(algorithm.validate(valid).isEmpty());
        MatrixMultiplyInput input = algorithm.prepareInput(valid, CONTEXT);
        Matrix product = algorithm.computeSequential(input, CONTEXT);
        assertEquals(List.of(58.0, 64.0, 139.0, 154.0),
                List.of(product.get(0, 0), product.get(0, 1), product.get(1, 0), product.get(1, 1)));
        assertTrue(algorithm.verify(input, product, CONTEXT).passed());

        raw.put("rowsB", 4);
        assertEquals(Violation.Codes.DIMENSION_MISMATCH, algorithm.validate(validate(algorithm, raw)).get(0).code());
        raw.remove("rowsB");
        assertEquals(Violation.Codes.REQUIRED, algorithm.validate(validate(algorithm, raw)).get(0).code());
        raw.put("rowsB", 3);
        raw.put("matrixB", List.of(1, 2, 3));
        assertEquals(Violation.Codes.DIMENSION_MISMATCH, algorithm.validate(validate(algorithm, raw)).get(0).code());
    }

    @Test
    void addAlgorithmComputesVerifiesAndRejectsOversizedRequests() {
        MatrixAddAlgorithm algorithm = new MatrixAddAlgorithm();
        Parameters parameters = validate(algorithm, Map.of("rows", 300, "cols", 200));
        MatrixPair input = algorithm.prepareInput(parameters, CONTEXT);
        Matrix sum = algorithm.computeSequential(input, CONTEXT);
        assertEquals(input.a().get(299, 199) + input.b().get(299, 199), sum.get(299, 199), 0.0);
        assertTrue(algorithm.verify(input, sum, CONTEXT).passed());
        sum.data()[123] = Math.nextUp(sum.data()[123]);
        VerificationReport report = algorithm.verify(input, sum, CONTEXT);
        assertFalse(report.passed());
        assertEquals(1L, report.details().get("mismatches"));

        List<Violation> tooLarge = algorithm.validate(validate(algorithm, Map.of("rows", 100_000, "cols", 100_000)));
        assertEquals(Violation.Codes.TOO_LARGE, tooLarge.get(0).code());
    }

    @Test
    void profilesEstimateOperationsAndMemoryWithoutAllocating() {
        MatrixMultiplyAlgorithm algorithm = new MatrixMultiplyAlgorithm();
        var profile = algorithm.analyze(validate(algorithm,
                Map.of("rowsA", 20_000, "colsA", 20_000, "colsB", 20_000)));
        assertEquals(2.0 * 20_000 * 20_000 * 20_000, profile.estimatedOperations());
        assertTrue(profile.estimatedPeakMemoryBytes() > 9_000_000_000L, "three 20000x20000 double matrices");
    }

    private static Parameters validate(Object algorithm, Map<String, Object> raw) {
        var descriptor = algorithm instanceof MatrixMultiplyAlgorithm m ? m.descriptor()
                : ((MatrixAddAlgorithm) algorithm).descriptor();
        return ParameterValidator.validate(descriptor.parameters(), ParameterValues.normalizeAll(raw));
    }
}
