package org.districompute.core.algorithm.primes;

import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Partial result of a prime-counting partition. It echoes the sub-range, so the coordinator can check that the
 * count belongs to the partition it claims to answer.
 *
 * @param lower inclusive lower bound of the sub-range
 * @param upper inclusive upper bound of the sub-range
 * @param count primes in the sub-range
 */
public record PrimeCount(long lower, long upper, long count) {

    /** Binary codec, format {@code primes-count/1}: lower, upper, count (int64). */
    public static final PayloadCodec<PrimeCount> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "primes-count/1";
        }

        @Override
        public byte[] encode(PrimeCount value) {
            return new BinaryPayloadWriter(format(), 24).putLong(value.lower()).putLong(value.upper())
                    .putLong(value.count()).finish();
        }

        @Override
        public PrimeCount decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            PrimeCount value = new PrimeCount(reader.getLong(), reader.getLong(), reader.getLong());
            reader.requireFullyConsumed();
            return value;
        }
    };

    /** Binary codec for work units ({@link PrimeRange}), format {@code primes-range/1}: lower, upper (int64). */
    public static final PayloadCodec<PrimeRange> RANGE_CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "primes-range/1";
        }

        @Override
        public byte[] encode(PrimeRange value) {
            return new BinaryPayloadWriter(format(), 16).putLong(value.lower()).putLong(value.upper()).finish();
        }

        @Override
        public PrimeRange decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            long lower = reader.getLong();
            long upper = reader.getLong();
            reader.requireFullyConsumed();
            try {
                return new PrimeRange(lower, upper);
            } catch (IllegalArgumentException e) {
                throw new org.districompute.core.DistriComputeException(
                        org.districompute.core.ErrorCode.SERIALIZATION_FAILED, e.getMessage(), e);
            }
        }
    };
}
