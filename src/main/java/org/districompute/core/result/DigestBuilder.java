package org.districompute.core.result;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Computes the canonical SHA-256 digest of a result ({@code sha256:<hex>}).
 *
 * <p>Canonical encoding: a UTF-8 type tag (length-prefixed), followed by the values in little-endian order. Longs
 * use 8 bytes. Doubles use their raw IEEE-754 bits. Two results have equal digests exactly when their encodings
 * are byte-identical, so equal digests across execution modes mean bitwise-identical results.
 */
public final class DigestBuilder {

    private static final int BUFFER_BYTES = 1 << 16;

    private final MessageDigest digest;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN);

    /**
     * Starts a digest with a type tag.
     *
     * @param type result type tag, for example {@code "matrix-f64"}
     */
    public DigestBuilder(String type) {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
        putString(type);
    }

    /**
     * Appends a long.
     *
     * @param value value
     * @return this builder
     */
    public DigestBuilder putLong(long value) {
        ensure(Long.BYTES);
        buffer.putLong(value);
        return this;
    }

    /**
     * Appends a double's raw IEEE-754 bits.
     *
     * @param value value
     * @return this builder
     */
    public DigestBuilder putDouble(double value) {
        return putLong(Double.doubleToRawLongBits(value));
    }

    /**
     * Appends a length-prefixed UTF-8 string.
     *
     * @param value value
     * @return this builder
     */
    public DigestBuilder putString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putLong(bytes.length);
        flush();
        digest.update(bytes);
        return this;
    }

    /**
     * Appends length-prefixed raw bytes.
     *
     * @param bytes bytes to append
     * @return this builder
     */
    public DigestBuilder putBytes(byte[] bytes) {
        putLong(bytes.length);
        flush();
        digest.update(bytes);
        return this;
    }

    /**
     * Appends a range of longs.
     *
     * @param values array
     * @param from first index, inclusive
     * @param to last index, exclusive
     * @return this builder
     */
    public DigestBuilder putLongs(long[] values, int from, int to) {
        int index = from;
        while (index < to) {
            if (buffer.remaining() < Long.BYTES) {
                flush();
            }
            int count = Math.min(to - index, buffer.remaining() / Long.BYTES);
            buffer.asLongBuffer().put(values, index, count);
            buffer.position(buffer.position() + count * Long.BYTES);
            index += count;
        }
        return this;
    }

    /**
     * Appends a range of doubles (raw IEEE-754 bits).
     *
     * @param values array
     * @param from first index, inclusive
     * @param to last index, exclusive
     * @return this builder
     */
    public DigestBuilder putDoubles(double[] values, int from, int to) {
        int index = from;
        while (index < to) {
            if (buffer.remaining() < Double.BYTES) {
                flush();
            }
            int count = Math.min(to - index, buffer.remaining() / Double.BYTES);
            buffer.asDoubleBuffer().put(values, index, count);
            buffer.position(buffer.position() + count * Double.BYTES);
            index += count;
        }
        return this;
    }

    /**
     * Finishes the digest.
     *
     * @return {@code sha256:} followed by 64 lowercase hex digits
     */
    public String finish() {
        flush();
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private void ensure(int bytes) {
        if (buffer.remaining() < bytes) {
            flush();
        }
    }

    private void flush() {
        buffer.flip();
        digest.update(buffer);
        buffer.clear();
    }
}
