package org.districompute.core.serialization;

import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.zip.CRC32C;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;

/**
 * JSON envelope for a binary payload (integration contract, section 6). The transport may instead send the raw
 * bytes out-of-band and only exchange {@code format}, {@code sizeBytes} and {@code crc32c}.
 *
 * @param format payload format, for example {@code "matrix-block/1"}
 * @param sizeBytes length of the decoded bytes
 * @param crc32c CRC-32C of the decoded bytes as 8 lowercase hex digits
 * @param base64 the bytes, base64-encoded (RFC 4648, with padding)
 */
public record EncodedPayload(String format, int sizeBytes, String crc32c, String base64) {

    /** Validates the components. */
    public EncodedPayload {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(crc32c, "crc32c");
        Objects.requireNonNull(base64, "base64");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
    }

    /**
     * Wraps encoded bytes.
     *
     * @param format payload format
     * @param bytes encoded bytes
     * @return the envelope
     */
    public static EncodedPayload of(String format, byte[] bytes) {
        return new EncodedPayload(format, bytes.length, crcHex(bytes), Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Decodes and integrity-checks the bytes.
     *
     * @return the bytes
     * @throws DistriComputeException with {@link ErrorCode#SERIALIZATION_FAILED} if the size or checksum differs or
     *     the base64 is malformed
     */
    public byte[] decodeBytes() {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new DistriComputeException(ErrorCode.SERIALIZATION_FAILED, "Malformed base64 payload", e);
        }
        if (bytes.length != sizeBytes || !crcHex(bytes).equals(crc32c)) {
            throw new DistriComputeException(ErrorCode.SERIALIZATION_FAILED,
                    "Payload size or checksum mismatch for format " + format);
        }
        return bytes;
    }

    private static String crcHex(byte[] bytes) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length);
        return HexFormat.of().toHexDigits((int) crc.getValue());
    }
}
