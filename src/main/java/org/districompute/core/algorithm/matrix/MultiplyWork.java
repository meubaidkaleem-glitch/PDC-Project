package org.districompute.core.algorithm.matrix;

import java.util.Objects;
import org.districompute.core.partition.BlockBounds;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Work unit of a matrix-multiplication partition: computes the result block {@code block} from rows
 * {@code [aRowOffset, aRowOffset + block.rows())} of {@code a} and columns
 * {@code [bColOffset, bColOffset + block.cols())} of {@code b}.
 *
 * <p>For local execution, {@code a} and {@code b} are the full operands (views, nothing is copied). After decoding
 * on a remote worker, they are compact bands ({@code rows × k} and {@code k × cols}) and both offsets are 0. The
 * kernel performs identical arithmetic in both cases.
 *
 * @param a left operand or its row band
 * @param aRowOffset first row of {@code a} used
 * @param b right operand or its column band
 * @param bColOffset first column of {@code b} used
 * @param block result block to compute
 * @param blockSize cache tile edge of the kernel
 */
public record MultiplyWork(Matrix a, int aRowOffset, Matrix b, int bColOffset, BlockBounds block, int blockSize) {

    /**
     * Binary codec, format {@code matrix-multiply-work/1}: blockSize, rowStart, rowEnd, colStart, colEnd, inner
     * (int32), then the A band ({@code rows × inner}) and the B band ({@code inner × cols}) as f64 values. Only
     * the data the partition needs is transmitted.
     */
    public static final PayloadCodec<MultiplyWork> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "matrix-multiply-work/1";
        }

        @Override
        public byte[] encode(MultiplyWork work) {
            BlockBounds block = work.block();
            int inner = work.a().cols();
            long aValues = (long) block.rows() * inner;
            long bValues = (long) inner * block.cols();
            BinaryPayloadWriter writer = new BinaryPayloadWriter(format(), 24L + 8L * (aValues + bValues));
            writer.putInt(work.blockSize()).putInt(block.rowStart()).putInt(block.rowEnd())
                    .putInt(block.colStart()).putInt(block.colEnd()).putInt(inner);
            writer.putDoubles(work.a().data(), work.aRowOffset() * inner, (int) aValues);
            for (int p = 0; p < inner; p++) {
                writer.putDoubles(work.b().data(), p * work.b().cols() + work.bColOffset(), block.cols());
            }
            return writer.finish();
        }

        @Override
        public MultiplyWork decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            int blockSize = reader.getBoundedInt("blockSize", Integer.MAX_VALUE);
            BlockBounds block = new BlockBounds(reader.getBoundedInt("rowStart", Integer.MAX_VALUE),
                    reader.getBoundedInt("rowEnd", Integer.MAX_VALUE),
                    reader.getBoundedInt("colStart", Integer.MAX_VALUE),
                    reader.getBoundedInt("colEnd", Integer.MAX_VALUE));
            int inner = reader.getBoundedInt("inner", Integer.MAX_VALUE);
            Matrix aBand = new Matrix(block.rows(), inner, reader.getDoubles((long) block.rows() * inner));
            Matrix bBand = new Matrix(inner, block.cols(), reader.getDoubles((long) inner * block.cols()));
            reader.requireFullyConsumed();
            return new MultiplyWork(aBand, 0, bBand, 0, block, blockSize);
        }
    };

    /** Validates that the bands contain the block. */
    public MultiplyWork {
        Objects.requireNonNull(a, "a");
        Objects.requireNonNull(b, "b");
        Objects.requireNonNull(block, "block");
        if (a.cols() != b.rows()) {
            throw new IllegalArgumentException("Inner dimensions differ");
        }
        if (aRowOffset < 0 || aRowOffset + block.rows() > a.rows() || bColOffset < 0
                || bColOffset + block.cols() > b.cols()) {
            throw new IllegalArgumentException("Block " + block + " is outside the supplied operands");
        }
        if (blockSize < 1) {
            throw new IllegalArgumentException("blockSize must be at least 1");
        }
    }
}
