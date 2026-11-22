package org.districompute.core.algorithm.matrix;

import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ProgressReporter;

/**
 * Low-level, strided matrix kernels. The same kernel serves sequential execution (whole matrices), local
 * partitions (views into shared matrices) and remote partitions (compact sub-matrices), so all modes perform
 * exactly the same floating-point operations in the same order.
 *
 * <p>Element {@code (i, j)} of an operand described by {@code (array, offset, stride)} is
 * {@code array[offset + i * stride + j]}.
 */
public final class MatrixKernels {

    private MatrixKernels() {
    }

    /**
     * Cache-blocked {@code C += A × B} over a {@code rows × inner} by {@code inner × cols} product.
     *
     * <p>Loop order {@code i0, p0, j0, i, p, j} with square tiles of edge {@code block}. For every element
     * {@code C[i][j]} the products {@code A[i][p] * B[p][j]} are accumulated in ascending {@code p}, starting from
     * the existing value of {@code C[i][j]}. With {@code C} initially zero, the result is therefore bitwise
     * identical to the naive triple loop, for every block size and every row or column decomposition. Java
     * floating point is strict (IEEE-754, no fused multiply-add here), so this also holds across JVMs.
     *
     * @param a left operand array
     * @param aOffset offset of {@code A[0][0]}
     * @param aStride row stride of {@code A}
     * @param b right operand array
     * @param bOffset offset of {@code B[0][0]}
     * @param bStride row stride of {@code B}
     * @param c accumulator array
     * @param cOffset offset of {@code C[0][0]}
     * @param cStride row stride of {@code C}
     * @param rows rows of {@code A} and {@code C}
     * @param inner columns of {@code A} = rows of {@code B}
     * @param cols columns of {@code B} and {@code C}
     * @param block tile edge, at least 1
     * @param cancellation polled once per {@code (row tile, inner tile)} pair
     * @param progress advanced by the number of rows completed, once per row tile
     */
    public static void multiplyAdd(double[] a, int aOffset, int aStride, double[] b, int bOffset, int bStride,
            double[] c, int cOffset, int cStride, int rows, int inner, int cols, int block,
            CancellationToken cancellation, ProgressReporter progress) {
        if (block < 1) {
            throw new IllegalArgumentException("block must be at least 1");
        }
        for (int i0 = 0; i0 < rows; i0 += block) {
            int i1 = Math.min(i0 + block, rows);
            for (int p0 = 0; p0 < inner; p0 += block) {
                cancellation.throwIfCancelled();
                int p1 = Math.min(p0 + block, inner);
                for (int j0 = 0; j0 < cols; j0 += block) {
                    int j1 = Math.min(j0 + block, cols);
                    for (int i = i0; i < i1; i++) {
                        int aRow = aOffset + i * aStride;
                        int cRow = cOffset + i * cStride;
                        for (int p = p0; p < p1; p++) {
                            double aip = a[aRow + p];
                            int bRow = bOffset + p * bStride;
                            for (int j = j0; j < j1; j++) {
                                c[cRow + j] += aip * b[bRow + j];
                            }
                        }
                    }
                }
            }
            progress.advance(i1 - i0);
        }
    }

    /**
     * Element-wise {@code c[i] = a[i] + b[i]} for {@code i} in {@code [from, to)}.
     *
     * @param a left operand
     * @param b right operand
     * @param c result
     * @param from first index, inclusive
     * @param to last index, exclusive
     */
    public static void add(double[] a, double[] b, double[] c, int from, int to) {
        for (int i = from; i < to; i++) {
            c[i] = a[i] + b[i];
        }
    }
}
