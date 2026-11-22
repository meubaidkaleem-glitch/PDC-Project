package org.districompute.core.algorithm.matrix;

import java.util.Objects;
import org.districompute.core.partition.BlockBounds;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Work unit of a matrix-addition partition: the block {@code block} of {@code A + B}, read from {@code a} and
 * {@code b} starting at {@code (rowOffset, colOffset)}. Locally the operands are the full matrices (views). After
 * decoding they are the compact sub-blocks and both offsets are 0.
 *
 * @param a left operand or its sub-block
 * @param b right operand or its sub-block (same shape as {@code a})
 * @param rowOffset first row read from the operands
 * @param colOffset first column read from the operands
 * @param block result block to compute
 */
public record AddWork(Matrix a, Matrix b, int rowOffset, int colOffset, BlockBounds block) {

    /** Binary codec, format {@code matrix-add-work/1}: rowStart, rowEnd, colStart, colEnd, then both sub-blocks. */
    public static final PayloadCodec<AddWork> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "matrix-add-work/1";
        }

        @Override
        public byte[] encode(AddWork work) {
            BlockBounds block = work.block();
            long elements = block.elementCount();
            BinaryPayloadWriter writer = new BinaryPayloadWriter(format(), 16L + 16L * elements);
            writer.putInt(block.rowStart()).putInt(block.rowEnd()).putInt(block.colStart()).putInt(block.colEnd());
            for (Matrix operand : new Matrix[] {work.a(), work.b()}) {
                for (int r = 0; r < block.rows(); r++) {
                    writer.putDoubles(operand.data(), (work.rowOffset() + r) * operand.cols() + work.colOffset(),
                            block.cols());
                }
            }
            return writer.finish();
        }

        @Override
        public AddWork decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            BlockBounds block = new BlockBounds(reader.getBoundedInt("rowStart", Integer.MAX_VALUE),
                    reader.getBoundedInt("rowEnd", Integer.MAX_VALUE),
                    reader.getBoundedInt("colStart", Integer.MAX_VALUE),
                    reader.getBoundedInt("colEnd", Integer.MAX_VALUE));
            Matrix a = new Matrix(block.rows(), block.cols(), reader.getDoubles(block.elementCount()));
            Matrix b = new Matrix(block.rows(), block.cols(), reader.getDoubles(block.elementCount()));
            reader.requireFullyConsumed();
            return new AddWork(a, b, 0, 0, block);
        }
    };

    /** Validates that the operands contain the block. */
    public AddWork {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(block, "block");
        if (a.rows() != b.rows() || a.cols() != b.cols()) {
            throw new IllegalArgumentException("Operand shapes differ");
        }
        if (rowOffset < 0 || colOffset < 0 || rowOffset + block.rows() > a.rows()
                || colOffset + block.cols() > a.cols()) {
            throw new IllegalArgumentException("Block " + block + " is outside the supplied operands");
        }
    }
}
