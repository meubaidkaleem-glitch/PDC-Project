package org.districompute.core.algorithm.matrix;

import java.util.Objects;

/**
 * Self-contained matrix-multiplication problem: the operands plus the kernel's tile edge. Carrying the
 * configuration with the data means a partition or remote worker needs nothing else to reproduce the computation.
 *
 * @param a left operand ({@code m × k})
 * @param b right operand ({@code k × n})
 * @param blockSize cache tile edge, at least 1
 */
public record MatrixMultiplyInput(Matrix a, Matrix b, int blockSize) {

    /** Validates the components and the inner dimensions. */
    public MatrixMultiplyInput {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        if (a.cols() != b.rows()) {
            throw new IllegalArgumentException("Inner dimensions differ: " + a.rows() + "x" + a.cols() + " times "
                    + b.rows() + "x" + b.cols());
        }
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be at least 1");
        }
    }
}
