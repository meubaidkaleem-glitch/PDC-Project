package org.districompute.core.serialization;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * Reads a payload written by {@link BinaryPayloadWriter}. The constructor verifies the magic number, the checksum
 * and the format name before anything is read. Every length is checked against the remaining bytes before an
 * array is allocated. All problems are reported as {@link ErrorCode#SERIALIZATION_FAILED}.
 */
public final class BinaryPayloadReader {

    private final ByteBuffer buffer;

    /**
     * Opens and validates a payload.
     *
     * @param bytes framed payload
     * @param expectedFormat the format this reader understands
     */
    public BinaryPayloadReader(byte[] bytes, String expectedFormat) {
        if (bytes == null || bytes.length < 12) {
            throw failure("Payload too short");
        }
        ByteBuffer whole = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int storedCrc = whole.getInt(bytes.length - Integer.BYTES);
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - Integer.BYTES);
        if ((int) crc.getValue() != storedCrc) {
            throw failure("Checksum mismatch: payload is corrupted");
        }
        if (whole.getInt() != BinaryPayloadWriter.MAGIC) {
            throw failure("Not a DistriCompute payload (bad magic number)");
        }
        int nameLength = whole.getInt();
        if (nameLength < 0 || nameLength > whole.remaining() - Integer.BYTES) {
            throw failure("Corrupted format name length");
        }
        byte[] name = new byte[nameLength];
        whole.get(name);
        String format = new String(name, StandardCharsets.UTF_8);
        if (!format.equals(expectedFormat)) {
            throw failure("Expected payload format " + expectedFormat + " but got " + format);
        }
        whole.limit(bytes.length - Integer.BYTES);
        this.buffer = whole.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Reads an int.
     *
     * @return the value
     */
    public int getInt() {
        try {
            return buffer.getInt();
        } catch (BufferUnderflowException e) {
            throw failure("Truncated payload");
        }
    }

    /**
     * Reads a long.
     *
     * @return the value
     */
    public long getLong() {
        try {
            return buffer.getLong();
        } catch (BufferUnderflowException e) {
            throw failure("Truncated payload");
        }
    }

    /**
     * Reads a non-negative int that is a length or index, bounded by {@code max}.
     *
     * @param what field name for error messages
     * @param max inclusive maximum
     * @return the value
     */
    public int getBoundedInt(String what, long max) {
        int value = getInt();
        if (value < 0 || value > max) {
            throw failure("Field " + what + " out of range: " + value);
        }
        return value;
    }

    /**
     * Reads {@code count} longs after checking that they are present.
     *
     * @param count number of values
     * @return the values
     */
    public long[] getLongs(long count) {
        requireRemaining(count, Long.BYTES);
        long[] values = new long[(int) count];
        buffer.asLongBuffer().get(values);
        buffer.position(buffer.position() + values.length * Long.BYTES);
        return values;
    }

    /**
     * Reads {@code count} doubles after checking that they are present.
     *
     * @param count number of values
     * @return the values
     */
    public double[] getDoubles(long count) {
        requireRemaining(count, Double.BYTES);
        double[] values = new double[(int) count];
        buffer.asDoubleBuffer().get(values);
        buffer.position(buffer.position() + values.length * Double.BYTES);
        return values;
    }

    /** Fails unless every body byte has been read. */
    public void requireFullyConsumed() {
        if (buffer.hasRemaining()) {
            throw failure(buffer.remaining() + " unexpected trailing bytes");
        }
    }

    private void requireRemaining(long count, int width) {
        if (count < 0 || count > buffer.remaining() / width) {
            throw failure("Declared " + count + " values but only " + buffer.remaining() / width + " are present");
        }
    }

    private static DistriComputeException failure(String message) {
        return new DistriComputeException(ErrorCode.SERIALIZATION_FAILED, message);
    }
}
