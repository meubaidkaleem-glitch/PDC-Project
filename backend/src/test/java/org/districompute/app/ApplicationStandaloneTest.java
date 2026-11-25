package org.districompute.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.districompute.app.config.AppProperties;
import org.districompute.app.standalone.StandaloneSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The integration tests with the standalone profile: the embedded H2 database in the data directory with its own
 * migrations, as in the Windows and Linux packages ({@code docs/standalone.md}); plus the console notice.
 */
@ActiveProfiles({"standalone", "test"})
class ApplicationStandaloneTest extends ApplicationIntegrationTest {

    @Autowired
    private StandaloneSupport standalone;
    @Autowired
    private AppProperties properties;

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("districompute.data-dir", () -> newDataDirectory("standalone"));
    }

    @Test
    void theConsoleShowsTheInitialPasswordOnlyWhileItIsTheAdministratorsPassword() throws IOException {
        Path file = properties.dataDir().resolve("initial-admin-password");
        try {
            Files.writeString(file, ADMIN_PASSWORD);
            String valid = notice();
            assertTrue(valid.contains("DistriCompute is running at http://localhost:1/"), valid);
            assertTrue(valid.contains("First sign-in:  user admin   password " + ADMIN_PASSWORD), valid);

            Files.writeString(file, "a-password-that-was-changed-since");
            String changed = notice();
            assertFalse(changed.contains("a-password-that-was-changed-since"), changed);
            assertFalse(changed.contains("First sign-in"), changed);

            Files.delete(file);
            assertFalse(notice().contains("First sign-in"));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private String notice() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        standalone.printSignIn(new PrintStream(bytes, true, StandardCharsets.UTF_8), "http://localhost:1/");
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
