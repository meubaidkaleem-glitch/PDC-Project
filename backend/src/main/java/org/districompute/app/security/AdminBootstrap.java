package org.districompute.app.security;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import org.districompute.app.config.AppProperties;
import org.districompute.app.config.SecretFiles;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.AppUserRepository;
import org.districompute.app.persistence.Enums.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator when the database has no account. The password comes from
 * {@code DISTRICOMPUTE_ADMIN_PASSWORD}, else from {@code districompute.security.admin-password-file}, else a random
 * password is written to {@code <dataDir>/initial-admin-password} (owner-only); it is never logged.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    /** Environment variable with the first administrator's password. */
    public static final String PASSWORD_VARIABLE = "DISTRICOMPUTE_ADMIN_PASSWORD";

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties properties;
    private final Clock clock;

    AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, AppProperties properties, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        if (users.count() > 0) {
            return;
        }
        String password = System.getenv(PASSWORD_VARIABLE);
        String source = PASSWORD_VARIABLE;
        if (password == null || password.isBlank()) {
            Path file = properties.security().adminPasswordFile();
            if (file != null) {
                password = SecretFiles.read(file);
                source = file.toString();
            } else {
                Path generated = properties.dataDir().resolve("initial-admin-password");
                password = SecretFiles.readOrCreate(generated, 18);
                source = generated.toAbsolutePath().toString();
            }
        }
        if (password.length() < 12) {
            throw new IllegalStateException("The first administrator's password (from " + source
                    + ") must have at least 12 characters");
        }
        String username = properties.security().adminUsername();
        users.save(new AppUser(username, encoder.encode(password), Role.ADMIN, clock.instant()));
        LOG.warn("Created the first administrator '{}' with the password from {}. Change it after signing in.",
                username, source);
    }
}
