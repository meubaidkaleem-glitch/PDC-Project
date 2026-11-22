package org.districompute.distributed.security;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import org.districompute.distributed.protocol.RegistrationProof;

/**
 * Mutual registration authentication with a shared secret ({@code docs/network-protocol.md}, "Authentication").
 *
 * <ol>
 *   <li>The worker sends {@code HMAC(secret, "DCP-REGISTER" | protocol version | worker id | nonce | issuedAt)}
 *       with a fresh random nonce.</li>
 *   <li>The coordinator recomputes it, checks that {@code issuedAt} is within the clock-skew window, and accepts
 *       each nonce only once within that window (replay protection).</li>
 *   <li>The coordinator answers with {@code HMAC(secret, "DCP-REGISTER-ACK" | worker id | nonce | session id)},
 *       which the worker checks, so a worker never executes tasks for an impostor coordinator.</li>
 * </ol>
 *
 * <p>The secret never travels over the network. Without TLS, messages after registration are not protected
 * against an attacker who can modify traffic on the network; enable TLS for untrusted networks.
 */
public final class RegistrationAuthenticator {

    /** The proof scheme implemented here. */
    public static final String SCHEME = "HMAC-SHA256";
    /** Default tolerated difference between worker and coordinator clocks. */
    public static final Duration DEFAULT_MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private static final int MAX_REMEMBERED_NONCES = 100_000;
    private static final HexFormat HEX = HexFormat.of();

    private final SharedSecret secret;
    private final Duration maxClockSkew;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> usedNonces = new HashMap<>();

    /**
     * Creates an authenticator.
     *
     * @param secret the shared secret
     * @param maxClockSkew tolerated clock difference between the two machines
     * @param clock clock used for {@code issuedAt} and freshness checks
     */
    public RegistrationAuthenticator(SharedSecret secret, Duration maxClockSkew, Clock clock) {
        this.secret = Objects.requireNonNull(secret, "secret");
        this.maxClockSkew = Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxClockSkew.isNegative() || maxClockSkew.isZero()) {
            throw new IllegalArgumentException("maxClockSkew must be positive");
        }
    }

    /**
     * Creates an authenticator with the default clock-skew window and the system clock.
     *
     * @param secret the shared secret
     */
    public RegistrationAuthenticator(SharedSecret secret) {
        this(secret, DEFAULT_MAX_CLOCK_SKEW, Clock.systemUTC());
    }

    /**
     * Worker side: creates a fresh registration proof.
     *
     * @param protocolVersion protocol version of the {@code REGISTER} message
     * @param workerId the registering worker's id
     * @return the proof
     */
    public RegistrationProof prove(String protocolVersion, String workerId) {
        byte[] nonceBytes = new byte[18];
        random.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new RegistrationProof(SCHEME, nonce, issuedAt,
                HEX.formatHex(secret.hmac(registerString(protocolVersion, workerId, nonce, issuedAt))));
    }

    /**
     * Coordinator side: verifies a worker's proof and consumes its nonce.
     *
     * @param protocolVersion protocol version of the {@code REGISTER} message
     * @param workerId the worker id in the envelope
     * @param proof the proof from the payload
     * @throws AuthenticationException if the scheme is unknown, the signature is wrong, or the proof is stale or
     *     replayed
     */
    public synchronized void verify(String protocolVersion, String workerId, RegistrationProof proof)
            throws AuthenticationException {
        if (!SCHEME.equals(proof.scheme())) {
            throw new AuthenticationException("Unsupported authentication scheme " + proof.scheme());
        }
        byte[] expected = secret.hmac(registerString(protocolVersion, workerId, proof.nonce(), proof.issuedAt()));
        if (!MessageDigest.isEqual(expected, HEX.parseHex(proof.signature()))) {
            throw new AuthenticationException("Invalid registration proof (wrong shared secret?)");
        }
        Instant now = clock.instant();
        if (Duration.between(proof.issuedAt(), now).abs().compareTo(maxClockSkew) > 0) {
            throw new AuthenticationException("Registration proof issued at " + proof.issuedAt()
                    + " is outside the accepted clock window of " + maxClockSkew.toSeconds() + " s");
        }
        purgeExpired(now);
        if (usedNonces.containsKey(proof.nonce())) {
            throw new AuthenticationException("Registration proof was already used (replay)");
        }
        if (usedNonces.size() >= MAX_REMEMBERED_NONCES) {
            throw new AuthenticationException("Too many registrations within the clock window; retry later");
        }
        usedNonces.put(proof.nonce(), proof.issuedAt().plus(maxClockSkew));
    }

    /**
     * Coordinator side: proof returned in {@code REGISTER_ACK}.
     *
     * @param workerId the registered worker
     * @param proof the worker's proof
     * @param sessionId the new session id
     * @return lowercase hex HMAC
     */
    public String coordinatorProof(String workerId, RegistrationProof proof, String sessionId) {
        return HEX.formatHex(secret.hmac(ackString(workerId, proof.nonce(), sessionId)));
    }

    /**
     * Worker side: checks the coordinator's proof.
     *
     * @param workerId this worker's id
     * @param proof the proof this worker sent
     * @param sessionId the session id from {@code REGISTER_ACK}
     * @param coordinatorProof the proof from {@code REGISTER_ACK}
     * @return {@code true} if the coordinator knows the secret
     */
    public boolean verifyCoordinator(String workerId, RegistrationProof proof, String sessionId,
            String coordinatorProof) {
        if (coordinatorProof == null || coordinatorProof.length() != 64) {
            return false;
        }
        byte[] expected = secret.hmac(ackString(workerId, proof.nonce(), sessionId));
        try {
            return MessageDigest.isEqual(expected, HEX.parseHex(coordinatorProof));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void purgeExpired(Instant now) {
        Iterator<Map.Entry<String, Instant>> it = usedNonces.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(now)) {
                it.remove();
            }
        }
    }

    private static String registerString(String protocolVersion, String workerId, String nonce, Instant issuedAt) {
        return "DCP-REGISTER\n" + protocolVersion + "\n" + workerId + "\n" + nonce + "\n" + issuedAt.toEpochMilli();
    }

    private static String ackString(String workerId, String nonce, String sessionId) {
        return "DCP-REGISTER-ACK\n" + workerId + "\n" + nonce + "\n" + sessionId;
    }
}
