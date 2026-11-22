package org.districompute.distributed.protocol;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Proof that a registering worker knows the cluster's shared secret, without sending the secret
 * ({@code docs/network-protocol.md}, "Authentication").
 *
 * @param scheme proof scheme; this runtime implements {@code HMAC-SHA256}
 * @param nonce random value chosen by the worker (base64url, 16–64 characters); each nonce is accepted once
 * @param issuedAt when the proof was made; the coordinator rejects proofs outside its clock-skew window
 * @param signature lowercase hex HMAC-SHA256 of the canonical registration string
 */
public record RegistrationProof(String scheme, String nonce, Instant issuedAt, String signature) {

    private static final Pattern NONCE = Pattern.compile("^[A-Za-z0-9_-]{16,64}$");
    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** Validates the components. */
    public RegistrationProof {
        Checks.requireText(scheme, "scheme", 32);
        Objects.requireNonNull(issuedAt, "issuedAt");
        if (nonce == null || !NONCE.matcher(nonce).matches()) {
            throw new IllegalArgumentException("nonce must be 16-64 base64url characters");
        }
        if (signature == null || !HEX_SHA256.matcher(signature).matches()) {
            throw new IllegalArgumentException("signature must be 64 lowercase hex digits");
        }
    }
}
