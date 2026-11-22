package org.districompute.distributed.coordinator;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.districompute.distributed.communication.CloseReason;
import org.districompute.distributed.communication.Connection;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.DuplicateMessageFilter;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.MessageCodec;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.Payload;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.ProtocolException;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.security.AuthenticationException;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator side of one worker connection. The reader thread decodes frames, drops duplicates, checks the
 * direction and verifies registration proofs; everything that touches coordinator state is posted to the control
 * loop through {@link Inbound}.
 */
final class WorkerSession implements Connection.Listener {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerSession.class);

    /** Where the session delivers decoded messages; all methods run on the control loop. */
    interface Inbound {

        void onRegister(WorkerSession session, Envelope register);

        void onMessage(WorkerSession session, Envelope message, long receivedNanos, int frameBytes);

        void onClosed(WorkerSession session, CloseReason reason);
    }

    /** Lifecycle of the session; changed only on the control loop. */
    enum State {
        /** Connected; only {@code REGISTER} is allowed. */
        CONNECTED,
        /** {@code REGISTER} is being processed by the control loop. */
        REGISTERING,
        /** Registered. */
        REGISTERED,
        /** Closed. */
        CLOSED
    }

    private final long openedNanos = System.nanoTime();
    private final DuplicateMessageFilter duplicates = new DuplicateMessageFilter(4096);
    private final AtomicInteger protocolErrors = new AtomicInteger();
    private final RegistrationAuthenticator authenticator;
    private final int maxProtocolErrors;
    private final Consumer<Runnable> loop;
    private final Inbound inbound;
    private volatile Connection connection;
    private volatile State state = State.CONNECTED;
    private volatile boolean registerSeen;
    private volatile String workerId;
    private volatile String sessionId;

    WorkerSession(RegistrationAuthenticator authenticator, int maxProtocolErrors, Consumer<Runnable> loop,
            Inbound inbound) {
        this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
        this.maxProtocolErrors = maxProtocolErrors;
        this.loop = Objects.requireNonNull(loop, "loop");
        this.inbound = Objects.requireNonNull(inbound, "inbound");
    }

    void attach(Connection value) {
        this.connection = value;
    }

    @Override
    public void onFrame(Connection source, byte[] frame, long receivedNanos) {
        Envelope message;
        try {
            message = MessageCodec.decode(frame);
        } catch (ProtocolException e) {
            protocolError(e.code(), e.getMessage(), e.messageId(), null, e.fatal());
            return;
        }
        if (!duplicates.firstSeen(message.messageId())) {
            LOG.warn("{}: dropped duplicate message {} ({})", source, message.messageId(), message.type());
            return;
        }
        if (!message.type().canBeSentBy(MessageType.Role.WORKER)) {
            protocolError(ProtocolErrorCode.UNEXPECTED_MESSAGE, message.type() + " is sent by the coordinator, "
                    + "not by workers", message.messageId(), message.attemptRef(), false);
            return;
        }
        if (message.workerId() == null) {
            protocolError(ProtocolErrorCode.INVALID_MESSAGE, "Worker messages must carry workerId",
                    message.messageId(), null, false);
            return;
        }
        if (!registerSeen) {
            if (message.type() != MessageType.REGISTER) {
                protocolError(ProtocolErrorCode.NOT_REGISTERED, "The first message must be REGISTER",
                        message.messageId(), null, true);
                return;
            }
            try {
                authenticator.verify(message.protocolVersion(), message.workerId(),
                        message.payload(RegisterPayload.class).proof());
            } catch (AuthenticationException e) {
                LOG.warn("{}: registration of {} rejected: {}", source, message.workerId(), e.getMessage());
                protocolError(ProtocolErrorCode.AUTHENTICATION_FAILED, e.getMessage(), message.messageId(), null,
                        true);
                return;
            }
            registerSeen = true;
            loop.accept(() -> inbound.onRegister(this, message));
            return;
        }
        int bytes = frame.length;
        loop.accept(() -> inbound.onMessage(this, message, receivedNanos, bytes));
    }

    @Override
    public void onFrameTooLarge(Connection source, int limit) {
        send(error(ProtocolErrorCode.MESSAGE_TOO_LARGE, "Frames are limited to " + limit + " bytes", null, null,
                false, true));
    }

    @Override
    public void onClosed(Connection source, CloseReason reason, Throwable cause) {
        if (cause != null) {
            LOG.debug("{}: closed ({})", source, reason, cause);
        }
        loop.accept(() -> inbound.onClosed(this, reason));
    }

    /**
     * Encodes and queues a message.
     *
     * @param message the message
     * @return {@code true} if queued
     */
    boolean send(Envelope message) {
        Connection c = connection;
        return c != null && c.send(MessageCodec.encode(message));
    }

    /**
     * Sends a non-fatal error and closes the connection once too many errors occurred.
     *
     * @param code error code
     * @param text explanation
     * @param inReplyTo offending message id, or {@code null}
     * @param ref attempt concerned, or {@code null}
     * @param fatal whether to close the connection
     */
    void protocolError(ProtocolErrorCode code, String text, String inReplyTo, AttemptRef ref, boolean fatal) {
        boolean close = fatal || protocolErrors.incrementAndGet() >= maxProtocolErrors;
        String message = close && !fatal ? text + " (too many protocol errors; closing the connection)" : text;
        send(error(code, message, inReplyTo, ref, false, close));
        if (close) {
            closeAfterFlush();
        }
    }

    /**
     * Builds an {@code ERROR} message addressed to this worker.
     *
     * @param code error code
     * @param text explanation
     * @param inReplyTo the message answered, or {@code null}
     * @param ref attempt concerned, or {@code null}
     * @param retryable whether a retry could succeed
     * @param fatal whether the connection closes afterwards
     * @return the message
     */
    Envelope error(ProtocolErrorCode code, String text, String inReplyTo, AttemptRef ref, boolean retryable,
            boolean fatal) {
        return Envelope.builder(MessageType.ERROR).inReplyTo(inReplyTo).workerId(workerId).attempt(ref)
                .payload(new ErrorPayload(code, text, retryable, fatal, null));
    }

    /**
     * Builds a message addressed to this worker.
     *
     * @param type message type
     * @param ref attempt concerned, or {@code null}
     * @param inReplyTo message answered, or {@code null}
     * @param payload the payload
     * @return the message
     */
    Envelope message(MessageType type, AttemptRef ref, String inReplyTo, Payload payload) {
        return Envelope.builder(type).workerId(workerId).attempt(ref).inReplyTo(inReplyTo).payload(payload);
    }

    /**
     * Queues an already encoded frame.
     *
     * @param frame the frame
     * @return {@code true} if queued
     * @throws IllegalArgumentException if the frame exceeds the worker's limit
     */
    boolean sendFrame(byte[] frame) {
        Connection c = connection;
        return c != null && c.send(frame);
    }

    void closeAfterFlush() {
        Connection c = connection;
        if (c != null) {
            c.closeAfterFlush(CloseReason.LOCAL);
        }
    }

    void close() {
        Connection c = connection;
        if (c != null) {
            c.close(CloseReason.LOCAL);
        }
    }

    void registered(String id, String session) {
        this.workerId = id;
        this.sessionId = session;
        this.state = State.REGISTERED;
    }

    void setState(State value) {
        this.state = value;
    }

    State state() {
        return state;
    }

    String workerId() {
        return workerId;
    }

    String sessionId() {
        return sessionId;
    }

    long openedNanos() {
        return openedNanos;
    }

    Connection connection() {
        return connection;
    }

    @Override
    public String toString() {
        Connection c = connection;
        return "session[" + (workerId == null ? "unregistered" : workerId) + ", "
                + (c == null ? "-" : c.remoteAddress()) + "]";
    }
}
