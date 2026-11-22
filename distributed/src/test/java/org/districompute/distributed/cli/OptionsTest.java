package org.districompute.distributed.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.security.SharedSecret;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptionsTest {

    @Test
    void parsesSpaceAndEqualsFormsFlagsAndPositionals() {
        Options options = Options.parse(List.of("--port", "9000", "--bind=127.0.0.1", "--allow-fault-simulation",
                "task.json", "--max-workers", "3"), Set.of("allow-fault-simulation"));
        assertEquals(9000, options.getInt("port", 1));
        assertEquals("127.0.0.1", options.get("bind", null));
        assertTrue(options.getBoolean("allow-fault-simulation", false));
        assertEquals(3, options.getInt("max-workers", 1));
        assertEquals(List.of("task.json"), options.positional());
        assertEquals(Duration.ofSeconds(2), options.getMillis("heartbeat-interval-ms", Duration.ofSeconds(2)));
        assertFalse(options.has("nothing"));
    }

    @Test
    void reportsBadValuesAndUnknownOptions() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(List.of("--port", "x")).getInt("port", 1));
        assertThrows(IllegalArgumentException.class,
                () -> Options.parse(List.of("--flag", "maybe")).getBoolean("flag", false));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> Options.parse(List.of("--prot", "1")).requireKnown(Set.of("port")));
        assertTrue(unknown.getMessage().contains("--prot"));
    }

    @Test
    void configurationFilesProvideDefaultsThatTheCommandLineOverrides(@TempDir Path dir) throws IOException {
        Path secret = dir.resolve("secret");
        Files.writeString(secret, "a-shared-secret-of-sufficient-length\n", StandardCharsets.UTF_8);
        Path file = dir.resolve("coordinator.properties");
        Files.writeString(file, "port=7000\nbind=127.0.0.1\nheartbeat-interval-ms=500\nsuspect-after-ms=1500\n"
                + "heartbeat-timeout-ms=4000\nsecret-file=" + secret.toString().replace("\\", "/") + "\n",
                StandardCharsets.UTF_8);
        CoordinatorConfig config = CoordinatorCommand.config(Options.parse(List.of("--config", file.toString(),
                "--port", "7001", "--id", "lab-coordinator")));
        assertEquals(7001, config.port());
        assertEquals("127.0.0.1", config.bindAddress());
        assertEquals("lab-coordinator", config.coordinatorId());
        assertEquals(Duration.ofMillis(500), config.heartbeatInterval());
        assertEquals(Duration.ofMillis(4000), config.heartbeatTimeout());
        assertEquals(SharedSecret.of("a-shared-secret-of-sufficient-length"), config.secret());
        assertFalse(config.tls().enabled());
    }

    @Test
    void aMissingSecretIsExplained() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Secrets.resolve(Options.parse(List.of("--secret-file", "/does/not/exist"))));
        assertTrue(e.getMessage().contains("/does/not/exist"));
    }
}
