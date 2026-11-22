package org.districompute.core.algorithm.sort;

import java.util.Objects;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Work unit of a sort partition: elements {@code values[from, to)}, which sit at {@code offset} in the whole input.
 * Locally {@code values} is the whole input (a view). After decoding it is the compact chunk, with
 * {@code from = 0}.
 *
 * @param values source array (read-only)
 * @param from first index, inclusive
 * @param to last index, exclusive
 * @param offset position of {@code values[from]} in the whole input
 */
public record SortChunk(long[] values, int from, int to, long offset) {

    /** Binary codec, format {@code sort-chunk/1}: offset (int64), length (int32), elements (int64). */
    public static final PayloadCodec<SortChunk> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "sort-chunk/1";
        }

        @Override
        public byte[] encode(SortChunk chunk) {
            int length = chunk.to() - chunk.from();
            return new BinaryPayloadWriter(format(), 12L + 8L * length).putLong(chunk.offset()).putInt(length)
                    .putLongs(chunk.values(), chunk.from(), chunk.to()).finish();
        }

        @Override
        public SortChunk decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            long offset = reader.getLong();
            int length = reader.getBoundedInt("length", Integer.MAX_VALUE);
            long[] values = reader.getLongs(length);
            reader.requireFullyConsumed();
            if (offset < 0) {
                throw new org.districompute.core.DistriComputeException(
                        org.districompute.core.ErrorCode.SERIALIZATION_FAILED, "Negative chunk offset");
            }
            return new SortChunk(values, 0, length, offset);
        }
    };

    /** Validates the components. */
    public SortChunk {
        Objects.requireNonNull(values, "values");
        Objects.checkFromToIndex(from, to, values.length);
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }
    }
}
