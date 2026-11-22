package org.districompute.distributed.protocol;

/**
 * The protocol's message types, with the direction each may travel, the payload class and which ids the
 * envelope must carry.
 */
public enum MessageType {
    /** Worker asks to join, with its capabilities and a registration proof. */
    REGISTER(Direction.WORKER_TO_COORDINATOR, RegisterPayload.class, Scope.CONNECTION),
    /** Coordinator accepts a registration. */
    REGISTER_ACK(Direction.COORDINATOR_TO_WORKER, RegisterAckPayload.class, Scope.CONNECTION),
    /** Coordinator assigns one attempt of one partition. */
    TASK(Direction.COORDINATOR_TO_WORKER, TaskPayload.class, Scope.ATTEMPT),
    /** Worker has admitted the attempt and will execute it. */
    TASK_ACCEPTED(Direction.WORKER_TO_COORDINATOR, TaskAcceptedPayload.class, Scope.ATTEMPT),
    /** Worker reports kernel progress of a running attempt. */
    TASK_PROGRESS(Direction.WORKER_TO_COORDINATOR, TaskProgressPayload.class, Scope.ATTEMPT),
    /** Worker returns the partial result of an attempt. */
    RESULT(Direction.WORKER_TO_COORDINATOR, ResultPayload.class, Scope.ATTEMPT),
    /** Either side reports an error, possibly about one attempt. */
    ERROR(Direction.BOTH, ErrorPayload.class, Scope.OPTIONAL_ATTEMPT),
    /** Worker liveness and load report. */
    HEARTBEAT(Direction.WORKER_TO_COORDINATOR, HeartbeatPayload.class, Scope.CONNECTION),
    /** Coordinator acknowledges a heartbeat. */
    HEARTBEAT_ACK(Direction.COORDINATOR_TO_WORKER, HeartbeatAckPayload.class, Scope.CONNECTION),
    /** Coordinator asks the worker to stop an attempt. */
    CANCEL(Direction.COORDINATOR_TO_WORKER, CancelPayload.class, Scope.ATTEMPT),
    /** Either side announces that it is shutting down. */
    SHUTDOWN(Direction.BOTH, ShutdownPayload.class, Scope.CONNECTION);

    /** Who may send a message type. */
    public enum Direction {
        /** Only workers send it. */
        WORKER_TO_COORDINATOR,
        /** Only the coordinator sends it. */
        COORDINATOR_TO_WORKER,
        /** Both sides send it. */
        BOTH
    }

    /** Which attempt ids the envelope carries. */
    public enum Scope {
        /** No attempt ids: the message concerns the connection. */
        CONNECTION,
        /** {@code taskId}, {@code partitionId} and {@code attemptId} are required. */
        ATTEMPT,
        /** Either all three attempt ids or none of them. */
        OPTIONAL_ATTEMPT
    }

    /** The two roles of the protocol. */
    public enum Role {
        /** The coordinator process. */
        COORDINATOR,
        /** A worker process. */
        WORKER
    }

    private final Direction direction;
    private final Class<? extends Payload> payloadClass;
    private final Scope scope;

    MessageType(Direction direction, Class<? extends Payload> payloadClass, Scope scope) {
        this.direction = direction;
        this.payloadClass = payloadClass;
        this.scope = scope;
    }

    /**
     * Who may send this type.
     *
     * @return the direction
     */
    public Direction direction() {
        return direction;
    }

    /**
     * The payload class of this type.
     *
     * @return the class
     */
    public Class<? extends Payload> payloadClass() {
        return payloadClass;
    }

    /**
     * Which attempt ids the envelope carries.
     *
     * @return the scope
     */
    public Scope scope() {
        return scope;
    }

    /**
     * Whether a role may send this type.
     *
     * @param sender the sending role
     * @return {@code true} if allowed
     */
    public boolean canBeSentBy(Role sender) {
        return direction == Direction.BOTH
                || (sender == Role.WORKER ? direction == Direction.WORKER_TO_COORDINATOR
                        : direction == Direction.COORDINATOR_TO_WORKER);
    }
}
