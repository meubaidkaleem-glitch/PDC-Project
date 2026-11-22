package org.districompute.core.algorithm.matrix;

import java.util.Objects;

/**
 * The two operands of a binary matrix operation.
 *
 * @param a left operand
 * @param b right operand
 */
public record MatrixPair(Matrix a, Matrix b) {

    /** Validates the components. */
    public MatrixPair {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
    }
}
