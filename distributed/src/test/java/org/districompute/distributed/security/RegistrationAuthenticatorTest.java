package org.districompute.distributed.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.districompute.distributed.protocol.RegistrationProof;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegistrationAuthenticatorTest {

    private static final SharedSecret SECRET = SharedSecret.of("correct horse battery staple");
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final RegistrationAuthenticator worker = at(SECRET, NOW);
    private final RegistrationAuthenticator coordinator = at(SECRET, NOW);

    @Test
    void aValidProofIsAcceptedOnce() {
        RegistrationProof proof = worker.prove("0.1", "w1");
        assertDoesNotThrow(() -> coordinator.verify("0.1", "w1", proof));
        AuthenticationException replay = assertThrows(AuthenticationException.class,
                () -> coordinator.verify("0.1", "w1", proof));
        assertTrue(replay.getMessage().contains("replay"));
    }

    @Test
    void aWrongSecretIsRejected() {
        RegistrationProof proof = at(SharedSecret.of("some other secret value"), NOW).prove("0.1", "w1");
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w1", proof));
    }

    @Test
    void theProofIsBoundToWorkerIdAndProtocolVersion() {
        RegistrationProof proof = worker.prove("0.1", "w1");
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w2", proof));
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.2", "w1", proof));
    }

    @Test
    void staleAndFutureProofsAreRejected() {
        RegistrationProof old = at(SECRET, NOW.minus(Duration.ofMinutes(6))).prove("0.1", "w1");
        RegistrationProof future = at(SECRET, NOW.plus(Duration.ofMinutes(6))).prove("0.1", "w1");
        RegistrationProof skewed = at(SECRET, NOW.minus(Duration.ofMinutes(4))).prove("0.1", "w1");
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w1", old));
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w1", future));
        assertDoesNotThrow(() -> coordinator.verify("0.1", "w1", skewed), "within the 5 minute window");
    }

    @Test
    void aTamperedSignatureOrSchemeIsRejected() {
        RegistrationProof proof = worker.prove("0.1", "w1");
        char first = proof.signature().charAt(0);
        String flipped = (first == '0' ? '1' : '0') + proof.signature().substring(1);
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w1",
                new RegistrationProof(proof.scheme(), proof.nonce(), proof.issuedAt(), flipped)));
        assertThrows(AuthenticationException.class, () -> coordinator.verify("0.1", "w1",
                new RegistrationProof("PLAIN", proof.nonce(), proof.issuedAt(), proof.signature())));
    }

    @Test
    void theCoordinatorProvesTheSecretBack() {
        RegistrationProof proof = worker.prove("0.1", "w1");
        String ack = coordinator.coordinatorProof("w1", proof, "session-1");
        assertTrue(worker.verifyCoordinator("w1", proof, "session-1", ack));
        assertFalse(worker.verifyCoordinator("w1", proof, "session-2", ack));
        String impostor = at(SharedSecret.of("an impostor's secret!"), NOW).coordinatorProof("w1", proof, "session-1");
        assertFalse(worker.verifyCoordinator("w1", proof, "session-1", impostor));
        assertFalse(worker.verifyCoordinator("w1", proof, "session-1", "zz"));
        assertFalse(worker.verifyCoordinator("w1", proof, "session-1", "z".repeat(64)));
    }

    @Test
    void noncesAreFresh() {
        assertNotEquals(worker.prove("0.1", "w1").nonce(), worker.prove("0.1", "w1").nonce());
    }

    @Test
    void secretsAreLongEnoughAndNeverPrinted(@TempDir Path dir) throws IOException {
        assertThrows(IllegalArgumentException.class, () -> SharedSecret.of("short"));
        assertEquals("SharedSecret[redacted]", SECRET.toString());
        Path file = dir.resolve("secret.txt");
        Files.writeString(file, "correct horse battery staple\n", StandardCharsets.UTF_8);
        assertEquals(SECRET, SharedSecret.fromFile(file));
    }

    private static RegistrationAuthenticator at(SharedSecret secret, Instant instant) {
        return new RegistrationAuthenticator(secret, Duration.ofMinutes(5), Clock.fixed(instant, ZoneOffset.UTC));
    }
}
