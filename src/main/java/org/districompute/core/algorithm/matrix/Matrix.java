package org.districompute.core.algorithm.matrix;

import java.util.Arrays;
import java.util.Objects;
import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.result.DigestBuilder;

/**
 * Dense, row-major matrix of doubles: element {@code (i, j)} is stored at {@code data[i * cols + j]}.
 *
 * <p><strong>Ownership:</strong> to avoid copying multi-megabyte arrays, the constructor does not copy
 * {@code data} and {@link #data()} returns the backing array. Code that receives a {@code Matrix} must treat it as
 * read-only unless it created the matrix itself.
 */
public final class Matrix {

    private final int rows;
    private final int cols;
    private final double[] data;

    /**
     * Wraps a row-major array.
     *
     * @param rows number of rows, at least 0
     * @param cols number of columns, at least 0
     * @param data row-major elements; length must be {@code rows * cols}
     */
    public Matrix(int rows, int cols, double[] data) {
        if (rows < 0 || cols < 0) {
            throw new IllegalArgumentException("Matrix dimensions must be non-negative: " + rows + "x" + cols);
        }
        Objects.requireNonNull(data, "data");
        if ((long) rows * cols != data.length) {
            throw new IllegalArgumentException("A " + rows + "x" + cols + " matrix needs " + (long) rows * cols
                    + " elements, got " + data.length);
        }
        this.rows = rows;
        this.cols = cols;
        this.data = data;
    }

    /**
     * Creates a zero matrix.
     *
     * @param rows number of rows
     * @param cols number of columns
     * @return the matrix
     */
    public static Matrix zeros(int rows, int cols) {
        long elements = (long) rows * cols;
        if (rows < 0 || cols < 0 || !Sizes.fitsInArray(elements)) {
            throw new IllegalArgumentException("Cannot allocate a " + rows + "x" + cols + " matrix");
        }
        return new Matrix(rows, cols, new double[(int) elements]);
    }

    /**
     * Number of rows.
     *
     * @return rows
     */
    public int rows() {
        return rows;
    }

    /**
     * Number of columns.
     *
     * @return columns
     */
    public int cols() {
        return cols;
    }

    /**
     * Element {@code (row, col)}.
     *
     * @param row row index
     * @param col column index
     * @return the element
     */
    public double get(int row, int col) {
        Objects.checkIndex(row, rows);
        Objects.checkIndex(col, cols);
        return data[row * cols + col];
    }

    /**
     * Backing row-major array (shared, not copied).
     *
     * @return the elements
     */
    public double[] data() {
        return data;
    }

    /**
     * Whether both matrices have the same shape and bitwise-identical elements.
     *
     * @param other matrix to compare
     * @return {@code true} if identical
     */
    public boolean equalsExactly(Matrix other) {
        return rows == other.rows && cols == other.cols && Arrays.equals(data, other.data);
    }

    /**
     * Canonical digest of this matrix: type tag {@code matrix-f64}, rows, cols, then the elements in row-major
     * order.
     *
     * @return {@code sha256:<hex>}
     */
    public String digest() {
        return new DigestBuilder("matrix-f64").putLong(rows).putLong(cols).putDoubles(data, 0, data.length).finish();
    }

    @Override
    public String toString() {
        return "Matrix[" + rows + "x" + cols + "]";
    }
}
