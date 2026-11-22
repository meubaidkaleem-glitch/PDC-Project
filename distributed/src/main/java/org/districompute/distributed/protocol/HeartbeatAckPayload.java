package org.districompute.distributed.protocol;

/**
 * {@code HEARTBEAT_ACK}: the coordinator received a heartbeat.
 *
 * @param sequence the acknowledged heartbeat's sequence number
 * @param workerStatus the worker's status as the coordinator sees it, for example {@code ACTIVE}
 */
public record HeartbeatAckPayload(long sequence, String workerStatus) implements Payload {

    /** Validates the components. */
    public HeartbeatAckPayload {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        Checks.requireText(workerStatus, "workerStatus", 32);
    }
}
