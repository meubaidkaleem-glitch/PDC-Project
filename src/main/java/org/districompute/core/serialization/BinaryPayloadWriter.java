package org.districompute.core.serialization;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * Writes a framed binary payload:
 * <pre>
 *   magic "DCP1" (4 bytes) | format length (int32) | format name (UTF-8) | body | CRC-32C of everything before (int32)
 * </pre>
 * All numbers are little-endian. The body size must be declared up front, so the payload is allocated exactly
 * once and oversized payloads are rejected before any copying.
 */
public final class BinaryPayloadWriter {

    static final int MAGIC = 0x31504344; // "DCP1" read as a little-endian int
    private static final long MAX_PAYLOAD_BYTES = Integer.MAX_VALUE - 8L;

    private final ByteBuffer buffer;

    /**
     * Starts a payload.
     *
     * @param format format name and version, for example {@code "matrix-block/1"}
     * @param bodyBytes exact number of body bytes that will be written
     */
    public BinaryPayloadWriter(String format, long bodyBytes) {
        byte[] name = format.getBytes(StandardCharsets.UTF_8);
        long total = 4L + 4L + name.length + bodyBytes + 4L;
        if (bodyBytes < 0 || total > MAX_PAYLOAD_BYTES) {
            throw new DistriComputeException(ErrorCode.SERIALIZATION_FAILED,
                    "Payload of " + bodyBytes + " body bytes exceeds the 2 GiB single-message limit");
        }
        this.buffer = ByteBuffer.allocate((int) total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(MAGIC).putInt(name.length).put(name);
    }

    /**
     * Writes an int.
     *
     * @param value value
     * @return this writer
     */
    public BinaryPayloadWriter putInt(int value) {
        buffer.putInt(value);
        return this;
    }

    /**
     * Writes a long.
     *
     * @param value value
     * @return this writer
     */
    public BinaryPayloadWriter putLong(long value) {
        buffer.putLong(value);
        return this;
    }

    /**
     * Writes a range of longs.
     *
     * @param values source array
     * @param from first index, inclusive
     * @param to last index, exclusive
     * @return this writer
     */
    public BinaryPayloadWriter putLongs(long[] values, int from, int to) {
        buffer.asLongBuffer().put(values, from, to - from);
        buffer.position(buffer.position() + (to - from) * Long.BYTES);
        return this;
    }

    /**
     * Writes a range of doubles (IEEE-754 bits).
     *
     * @param values source array
     * @param from first index, inclusive
     * @param count number of values
     * @return this writer
     */
    public BinaryPayloadWriter putDoubles(double[] values, int from, int count) {
        buffer.asDoubleBuffer().put(values, from, count);
        buffer.position(buffer.position() + count * Double.BYTES);
        return this;
    }

    /**
     * Appends the checksum and returns the payload.
     *
     * @return the framed bytes
     * @throws IllegalStateException if the declared body size was not written exactly
     */
    public byte[] finish() {
        if (buffer.remaining() != Integer.BYTES) {
            throw new IllegalStateException("Body size mismatch: " + (buffer.remaining() - Integer.BYTES)
                    + " declared bytes were not written");
        }
        CRC32C crc = new CRC32C();
        crc.update(buffer.array(), 0, buffer.position());
        buffer.putInt((int) crc.getValue());
        return buffer.array();
    }
}
