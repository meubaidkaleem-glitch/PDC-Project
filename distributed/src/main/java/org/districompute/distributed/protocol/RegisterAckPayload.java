package org.districompute.distributed.protocol;

import java.util.regex.Pattern;

/**
 * {@code REGISTER_ACK}: the coordinator accepts a worker and tells it the cluster's timing and size settings.
 *
 * @param sessionId id of this registration (a new one on every reconnection)
 * @param coordinatorId identifier of the coordinator
 * @param runtimeVersion DistriCompute-Distributed version of the coordinator
 * @param heartbeatIntervalMillis how often the worker must send {@code HEARTBEAT}
 * @param heartbeatTimeoutMillis silence after which the coordinator declares the worker unavailable
 * @param maxMessageBytes largest frame the coordinator accepts
 * @param coordinatorProof HMAC proving that the coordinator knows the shared secret (lowercase hex)
 */
public record RegisterAckPayload(
        String sessionId,
        String coordinatorId,
        String runtimeVersion,
        long heartbeatIntervalMillis,
        long heartbeatTimeoutMillis,
        int maxMessageBytes,
        String coordinatorProof) implements Payload {

    private static final Pattern HEX_SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    /** Validates the components. */
    public RegisterAckPayload {
        Checks.requireText(sessionId, "sessionId", 64);
        Checks.requireText(coordinatorId, "coordinatorId", 128);
        Checks.optionalText(runtimeVersion, "runtimeVersion", 64);
        if (heartbeatIntervalMillis < 1 || heartbeatTimeoutMillis <= heartbeatIntervalMillis) {
            throw new IllegalArgumentException("Need 0 < heartbeatIntervalMillis < heartbeatTimeoutMillis");
        }
        if (maxMessageBytes < 1024) {
            throw new IllegalArgumentException("maxMessageBytes must be at least 1024");
        }
        if (coordinatorProof == null || !HEX_SHA256.matcher(coordinatorProof).matches()) {
            throw new IllegalArgumentException("coordinatorProof must be 64 lowercase hex digits");
        }
    }
}
