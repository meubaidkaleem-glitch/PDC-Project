package org.districompute.core.partition;

/**
 * A rectangular block of a matrix: rows {@code [rowStart, rowEnd)} and columns {@code [colStart, colEnd)}.
 *
 * @param rowStart first row, inclusive
 * @param rowEnd last row, exclusive
 * @param colStart first column, inclusive
 * @param colEnd last column, exclusive
 */
public record BlockBounds(int rowStart, int rowEnd, int colStart, int colEnd) implements PartitionBounds {

    /** Validates the block. */
    public BlockBounds {
        if (rowStart < 0 || colStart < 0 || rowEnd < rowStart || colEnd < colStart) {
            throw new IllegalArgumentException("Invalid block rows [" + rowStart + ", " + rowEnd + ") cols ["
                    + colStart + ", " + colEnd + ")");
        }
    }

    /**
     * Number of rows.
     *
     * @return {@code rowEnd - rowStart}
     */
    public int rows() {
        return rowEnd - rowStart;
    }

    /**
     * Number of columns.
     *
     * @return {@code colEnd - colStart}
     */
    public int cols() {
        return colEnd - colStart;
    }

    @Override
    public long elementCount() {
        return (long) rows() * cols();
    }
}
