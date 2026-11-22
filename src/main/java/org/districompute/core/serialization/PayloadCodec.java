package org.districompute.core.serialization;

/**
 * Explicit binary format for a partition work unit or partial result. Formats are named and versioned (for
 * example {@code "matrix-block/1"}), little-endian, and checksummed with CRC-32C (see {@link BinaryPayloadWriter}).
 * Decoders reject foreign formats, corrupted bytes and truncated or oversized content with
 * {@code SERIALIZATION_FAILED}. They never allocate based on unchecked lengths.
 *
 * @param <T> payload type
 */
public interface PayloadCodec<T> {

    /**
     * Name and version of the binary format.
     *
     * @return for example {@code "matrix-block/1"}
     */
    String format();

    /**
     * Encodes a value.
     *
     * @param value value to encode
     * @return the framed, checksummed bytes
     */
    byte[] encode(T value);

    /**
     * Decodes bytes produced by {@link #encode}.
     *
     * @param bytes framed bytes
     * @return the value
     */
    T decode(byte[] bytes);
}
