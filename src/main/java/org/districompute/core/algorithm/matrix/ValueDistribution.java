package org.districompute.core.algorithm.matrix;

import org.districompute.core.algorithm.support.CounterRandom;
import org.districompute.core.execution.CancellationToken;

/**
 * Deterministic element distributions for generated matrices. Element {@code e} (row-major index) of the matrix
 * generated on stream {@code s} is a pure function of {@code (seed, s, e)} (see {@link CounterRandom}).
 */
public enum ValueDistribution {
    /** Uniform doubles in {@code [-1, 1)}. */
    UNIFORM,
    /**
     * Small integers in {@code [-8, 8]} stored as doubles. Sums of products stay far below 2<sup>53</sup>, so
     * arithmetic on them is exact. Useful for exact cross-checks.
     */
    INTEGER;

    /**
     * Generates a matrix.
     *
     * @param rows number of rows
     * @param cols number of columns
     * @param seed generator seed
     * @param stream stream number (1 for the left operand, 2 for the right operand)
     * @param cancellation polled every 2<sup>20</sup> elements
     * @return the matrix
     */
    public Matrix generate(int rows, int cols, long seed, long stream, CancellationToken cancellation) {
        Matrix matrix = Matrix.zeros(rows, cols);
        double[] data = matrix.data();
        for (int e = 0; e < data.length; e++) {
            if ((e & 0xFFFFF) == 0) {
                cancellation.throwIfCancelled();
            }
            data[e] = this == UNIFORM
                    ? CounterRandom.symmetricAt(seed, stream, e)
                    : CounterRandom.boundedAt(seed, stream, e, 17) - 8;
        }
        return matrix;
    }
}
