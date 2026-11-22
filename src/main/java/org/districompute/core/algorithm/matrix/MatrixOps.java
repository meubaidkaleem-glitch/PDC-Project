package org.districompute.core.algorithm.matrix;

import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.Violation;

/**
 * Dimension-checked matrix operations for library callers. Incompatible shapes are rejected with a
 * {@link ValidationException} ({@code DIMENSION_MISMATCH}) before anything is allocated.
 */
public final class MatrixOps {

    private MatrixOps() {
    }

    /**
     * Computes {@code A × B} with the cache-blocked kernel.
     *
     * @param a left operand ({@code m × k})
     * @param b right operand ({@code k × n})
     * @param block tile edge for cache blocking
     * @param cancellation cancellation token
     * @param progress advanced by rows completed
     * @return the {@code m × n} product
     * @throws ValidationException if the inner dimensions differ or the result is too large
     */
    public static Matrix multiply(Matrix a, Matrix b, int block, CancellationToken cancellation,
            ProgressReporter progress) {
        if (a.cols() != b.rows()) {
            throw new ValidationException(new Violation("b", Violation.Codes.DIMENSION_MISMATCH,
                    "inner dimensions differ: A is " + a.rows() + "x" + a.cols() + " but B is " + b.rows() + "x"
                            + b.cols()));
        }
        requireAllocatable("result", (long) a.rows() * b.cols());
        Matrix c = Matrix.zeros(a.rows(), b.cols());
        MatrixKernels.multiplyAdd(a.data(), 0, a.cols(), b.data(), 0, b.cols(), c.data(), 0, c.cols(),
                a.rows(), a.cols(), b.cols(), block, cancellation, progress);
        return c;
    }

    /**
     * Computes {@code A + B}.
     *
     * @param a left operand
     * @param b right operand of the same shape
     * @param cancellation polled every 2<sup>20</sup> elements
     * @param progress advanced by rows completed
     * @return the sum
     * @throws ValidationException if the shapes differ
     */
    public static Matrix add(Matrix a, Matrix b, CancellationToken cancellation, ProgressReporter progress) {
        if (a.rows() != b.rows() || a.cols() != b.cols()) {
            throw new ValidationException(new Violation("b", Violation.Codes.DIMENSION_MISMATCH,
                    "shapes differ: A is " + a.rows() + "x" + a.cols() + " but B is " + b.rows() + "x" + b.cols()));
        }
        Matrix c = Matrix.zeros(a.rows(), a.cols());
        addRows(a, b, c, 0, a.rows(), cancellation, progress);
        return c;
    }

    /**
     * Adds rows {@code [rowStart, rowEnd)} of equally shaped matrices into {@code c}.
     *
     * @param a left operand
     * @param b right operand
     * @param c result, same shape
     * @param rowStart first row, inclusive
     * @param rowEnd last row, exclusive
     * @param cancellation polled about every 2<sup>20</sup> elements
     * @param progress advanced by rows completed
     */
    public static void addRows(Matrix a, Matrix b, Matrix c, int rowStart, int rowEnd,
            CancellationToken cancellation, ProgressReporter progress) {
        int cols = a.cols();
        int rowsPerChunk = Math.max(1, (1 << 20) / Math.max(1, cols));
        for (int r0 = rowStart; r0 < rowEnd; r0 += rowsPerChunk) {
            cancellation.throwIfCancelled();
            int r1 = Math.min(rowEnd, r0 + rowsPerChunk);
            MatrixKernels.add(a.data(), b.data(), c.data(), r0 * cols, r1 * cols);
            progress.advance(r1 - r0);
        }
    }

    private static void requireAllocatable(String field, long elements) {
        if (!Sizes.fitsInArray(elements)) {
            throw new ValidationException(new Violation(field, Violation.Codes.TOO_LARGE,
                    elements + " elements exceed the maximum array length " + Sizes.MAX_ARRAY_LENGTH));
        }
    }
}
