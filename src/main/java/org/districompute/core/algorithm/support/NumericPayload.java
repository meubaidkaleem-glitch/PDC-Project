package org.districompute.core.algorithm.support;

import java.util.Objects;
import java.util.function.Function;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Generic container for small numeric work units and partial results: a list of integers and a list of reals.
 * Algorithms map their own records onto it with {@link #codec}, so each format keeps its own name and version
 * without a hand-written binary layout. Layout: integer count (int32), integers (int64), real count (int32), reals
 * (f64).
 *
 * @param integers integer fields
 * @param reals real-valued fields
 */
public record NumericPayload(long[] integers, double[] reals) {

    /** Validates the components. */
    public NumericPayload {
        Objects.requireNonNull(integers, "integers");
        Objects.requireNonNull(reals, "reals");
    }

    /**
     * Builds a codec for a record type mapped onto this container.
     *
     * @param format format name and version, for example {@code "numeric-integrate-work/1"}
     * @param toPayload maps a value to the container
     * @param fromPayload maps the container back; may throw {@link IllegalArgumentException} for invalid content
     * @param <T> value type
     * @return the codec
     */
    public static <T> PayloadCodec<T> codec(String format, Function<T, NumericPayload> toPayload,
            Function<NumericPayload, T> fromPayload) {
        return new PayloadCodec<>() {
            @Override
            public String format() {
                return format;
            }

            @Override
            public byte[] encode(T value) {
                NumericPayload payload = toPayload.apply(value);
                long body = 8L + 8L * payload.integers().length + 8L * payload.reals().length;
                BinaryPayloadWriter writer = new BinaryPayloadWriter(format, body);
                writer.putInt(payload.integers().length).putLongs(payload.integers(), 0, payload.integers().length);
                writer.putInt(payload.reals().length).putDoubles(payload.reals(), 0, payload.reals().length);
                return writer.finish();
            }

            @Override
            public T decode(byte[] bytes) {
                BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format);
                long[] integers = reader.getLongs(reader.getBoundedInt("integerCount", Integer.MAX_VALUE));
                double[] reals = reader.getDoubles(reader.getBoundedInt("realCount", Integer.MAX_VALUE));
                reader.requireFullyConsumed();
                try {
                    return fromPayload.apply(new NumericPayload(integers, reals));
                } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                    throw new DistriComputeException(ErrorCode.SERIALIZATION_FAILED,
                            "Invalid " + format + " content: " + e.getMessage(), e);
                }
            }
        };
    }
}
