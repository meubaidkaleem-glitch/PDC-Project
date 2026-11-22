package org.districompute.distributed.cli;

import java.io.IOException;
import java.nio.file.Path;
import org.districompute.distributed.security.SharedSecret;

/**
 * Resolves the cluster's shared secret from {@code --secret-file} or the {@code DISTRICOMPUTE_SECRET} environment
 * variable. There is deliberately no command-line option for the secret itself: command lines are visible to other
 * users of the machine.
 */
public final class Secrets {

    /** Environment variable holding the secret. */
    public static final String ENVIRONMENT_VARIABLE = "DISTRICOMPUTE_SECRET";

    private Secrets() {
    }

    /**
     * Resolves the secret.
     *
     * @param options parsed options
     * @return the secret
     * @throws IllegalArgumentException if no secret is configured or the file cannot be read
     */
    public static SharedSecret resolve(Options options) {
        if (options.has("secret-file")) {
            Path file = Path.of(options.get("secret-file", ""));
            try {
                return SharedSecret.fromFile(file);
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read the secret file " + file + ": " + e.getMessage(), e);
            }
        }
        String fromEnvironment = System.getenv(ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return SharedSecret.of(fromEnvironment.strip());
        }
        throw new IllegalArgumentException("No shared secret: use --secret-file <file> or set "
                + ENVIRONMENT_VARIABLE + " (at least " + SharedSecret.MIN_BYTES + " bytes)");
    }
}
