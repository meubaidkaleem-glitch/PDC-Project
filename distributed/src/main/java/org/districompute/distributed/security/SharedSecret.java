package org.districompute.distributed.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The cluster's shared secret. Workers prove knowledge of it when they register, and the coordinator proves it
 * back; the secret itself never travels over the network. {@link #toString()} never reveals it.
 */
public final class SharedSecret {

    /** Minimum secret length in bytes. */
    public static final int MIN_BYTES = 16;

    private static final String HMAC = "HmacSHA256";

    private final byte[] key;

    private SharedSecret(byte[] key) {
        if (key.length < MIN_BYTES) {
            throw new IllegalArgumentException("The shared secret must have at least " + MIN_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    /**
     * Uses the UTF-8 bytes of a text as the secret.
     *
     * @param text the secret, at least 16 bytes in UTF-8
     * @return the secret
     */
    public static SharedSecret of(String text) {
        return new SharedSecret(Objects.requireNonNull(text, "text").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the secret from a file; surrounding whitespace (such as a final line feed) is ignored.
     *
     * @param file the file
     * @return the secret
     * @throws IOException if the file cannot be read
     */
    public static SharedSecret fromFile(Path file) throws IOException {
        return of(Files.readString(file, StandardCharsets.UTF_8).strip());
    }

    /**
     * Computes HMAC-SHA256 of a message under this secret.
     *
     * @param message the message, encoded as UTF-8
     * @return the 32-byte MAC
     */
    byte[] hmac(String message) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", e);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SharedSecret secret && Arrays.equals(key, secret.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    @Override
    public String toString() {
        return "SharedSecret[redacted]";
    }
}
