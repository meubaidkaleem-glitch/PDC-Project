package org.districompute.app.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/** Reads secrets from files, and creates random ones with owner-only permissions. */
public final class SecretFiles {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SecretFiles() {
    }

    /**
     * Reads a secret file (surrounding whitespace removed).
     *
     * @param file the file
     * @return the secret
     * @throws IOException if unreadable
     */
    public static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).strip();
    }

    /**
     * Returns the secret in {@code file}, creating the file with a new random secret if it does not exist.
     *
     * @param file the file
     * @param bytes random bytes for a new secret
     * @return the secret
     * @throws IOException if the file cannot be read or written
     */
    public static String readOrCreate(Path file, int bytes) throws IOException {
        if (Files.exists(file)) {
            return read(file);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try {
            Files.writeString(file, secret + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            return read(file);
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system (Windows): the directory's ACL applies.
        }
        return secret;
    }
}
