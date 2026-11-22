package org.districompute.core.algorithm.matrix;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.partition.BlockBounds;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Partial result of a matrix partition: the block of the result matrix whose top-left element is at
 * {@code (rowStart, colStart)}.
 *
 * @param rowStart first row of the block in the result
 * @param colStart first column of the block in the result
 * @param data the block's elements
 */
public record MatrixBlock(int rowStart, int colStart, Matrix data) {

    /** Binary codec, format {@code matrix-block/1}: rowStart, colStart, rows, cols (int32), elements (f64). */
    public static final PayloadCodec<MatrixBlock> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "matrix-block/1";
        }

        @Override
        public byte[] encode(MatrixBlock block) {
            Matrix m = block.data();
            BinaryPayloadWriter writer = new BinaryPayloadWriter(format(), 16L + 8L * m.data().length);
            writer.putInt(block.rowStart()).putInt(block.colStart()).putInt(m.rows()).putInt(m.cols());
            return writer.putDoubles(m.data(), 0, m.data().length).finish();
        }

        @Override
        public MatrixBlock decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            int rowStart = reader.getBoundedInt("rowStart", Integer.MAX_VALUE);
            int colStart = reader.getBoundedInt("colStart", Integer.MAX_VALUE);
            int rows = reader.getBoundedInt("rows", Integer.MAX_VALUE);
            int cols = reader.getBoundedInt("cols", Integer.MAX_VALUE);
            double[] values = reader.getDoubles((long) rows * cols);
            reader.requireFullyConsumed();
            return new MatrixBlock(rowStart, colStart, new Matrix(rows, cols, values));
        }
    };

    /** Validates the components. */
    public MatrixBlock {
        Objects.requireNonNull(data, "data");
        if (rowStart < 0 || colStart < 0) {
            throw new IllegalArgumentException("Block origin must be non-negative");
        }
    }

    /**
     * Checks a block against the partition it claims to fill: same origin, same shape, finite elements.
     *
     * @param bounds partition bounds
     * @param block received block
     * @return the problem, or empty if valid
     */
    public static Optional<String> problem(PartitionBounds bounds, MatrixBlock block) {
        if (!(bounds instanceof BlockBounds expected)) {
            return Optional.of("matrix partitions need BLOCK bounds, got " + bounds.getClass().getSimpleName());
        }
        if (block.rowStart() != expected.rowStart() || block.colStart() != expected.colStart()) {
            return Optional.of("block origin (" + block.rowStart() + ", " + block.colStart() + ") does not match "
                    + "partition origin (" + expected.rowStart() + ", " + expected.colStart() + ")");
        }
        if (block.data().rows() != expected.rows() || block.data().cols() != expected.cols()) {
            return Optional.of("block is " + block.data().rows() + "x" + block.data().cols() + ", partition needs "
                    + expected.rows() + "x" + expected.cols());
        }
        for (double value : block.data().data()) {
            if (!Double.isFinite(value)) {
                return Optional.of("block contains a non-finite element");
            }
        }
        return Optional.empty();
    }

    /**
     * Assembles validated blocks into the result matrix. The plan defines the result's extent.
     *
     * @param plan partition bounds, which tile the result exactly once
     * @param blocks validated blocks in plan order
     * @param cancellation polled once per block
     * @return the assembled matrix
     */
    public static Matrix assemble(List<PartitionBounds> plan, List<MatrixBlock> blocks,
            CancellationToken cancellation) {
        int rows = 0;
        int cols = 0;
        for (PartitionBounds bounds : plan) {
            BlockBounds block = (BlockBounds) bounds;
            rows = Math.max(rows, block.rowEnd());
            cols = Math.max(cols, block.colEnd());
        }
        Matrix result = Matrix.zeros(rows, cols);
        double[] target = result.data();
        for (MatrixBlock block : blocks) {
            cancellation.throwIfCancelled();
            Matrix source = block.data();
            for (int r = 0; r < source.rows(); r++) {
                System.arraycopy(source.data(), r * source.cols(), target, (block.rowStart() + r) * cols
                        + block.colStart(), source.cols());
            }
        }
        return result;
    }
}
