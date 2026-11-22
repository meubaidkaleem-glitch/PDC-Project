package org.districompute.distributed.coordinator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.districompute.core.CoreVersion;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.communication.CloseReason;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.HeartbeatAckPayload;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.RegisterAckPayload;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.protocol.ShutdownPayload;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerRegistry;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker connections, registration, heartbeats and health, on the control loop. Attempt messages are passed to
 * {@link AttemptMessages}; worker lifecycle changes are reported to {@link Listener}s.
 */
final class WorkerManager implements WorkerSession.Inbound {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerManager.class);

    /** Worker lifecycle notifications, on the control loop. */
    interface Listener {

        default void workerRegistered(WorkerRecord worker, boolean reconnection) {
        }

        default void workerStatusChanged(WorkerRecord worker, WorkerStatus previous, String reason) {
        }

        default void workerDisconnected(WorkerRecord worker, List<String> assignedAttempts, String reason) {
        }

        default void workerHeartbeat(WorkerRecord worker, HeartbeatPayload heartbeat) {
        }
    }

    /** Receives attempt messages from registered workers, on the control loop. */
    interface AttemptMessages {

        void onAttemptMessage(WorkerSession session, WorkerRecord worker, Envelope message, long receivedNanos,
                int frameBytes);
    }

    private final CoordinatorConfig config;
    private final WorkerRegistry registry;
    private final RegistrationAuthenticator authenticator;
    private final Set<WorkerSession> sessions = new LinkedHashSet<>();
    private final Map<String, WorkerSession> byWorker = new HashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private AttemptMessages attempts = WorkerManager::unknownAttempt;
    private RuntimeEvents events;
    private boolean shuttingDown;

    WorkerManager(CoordinatorConfig config, RegistrationAuthenticator authenticator) {
        this.config = config;
        this.authenticator = authenticator;
        this.registry = new WorkerRegistry(config.maxWorkers(), Math.max(64, config.maxWorkers()));
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void setAttemptMessages(AttemptMessages handler) {
        this.attempts = handler;
    }

    void setEvents(RuntimeEvents value) {
        this.events = value;
        addListener(value);
    }

    @Override
    public void onAuthenticationFailed(WorkerSession session, String workerId, String reason) {
        if (events != null) {
            events.workerRejected(workerId, reason);
        }
    }

    WorkerRegistry registry() {
        return registry;
    }

    Optional<WorkerSession> session(String workerId) {
        return Optional.ofNullable(byWorker.get(workerId));
    }

    /** A connection was accepted (control loop). */
    void accepted(WorkerSession session) {
        if (shuttingDown) {
            session.close();
            return;
        }
        long pending = sessions.stream().filter(s -> s.state() != WorkerSession.State.REGISTERED).count();
        if (pending >= config.maxPendingConnections()) {
            LOG.warn("Refusing {}: {} connections are already waiting to register", session, pending);
            session.close();
            return;
        }
        sessions.add(session);
    }

    @Override
    public void onRegister(WorkerSession session, Envelope register) {
        if (session.state() != WorkerSession.State.CONNECTED) {
            return;
        }
        String workerId = register.workerId();
        RegisterPayload payload = register.payload(RegisterPayload.class);
        WorkerCapabilities capabilities = payload.capabilities();
        if (shuttingDown) {
            reject(session, register, ProtocolErrorCode.SHUTTING_DOWN, "The coordinator is shutting down");
            return;
        }
        if (capabilities.faultSimulation() && !config.allowFaultSimulationWorkers()) {
            reject(session, register, ProtocolErrorCode.REGISTRATION_REJECTED, "This coordinator does not accept "
                    + "workers with fault simulation enabled (set allowFaultSimulationWorkers for experiments)");
            return;
        }
        if (!CoreVersion.version().equals(capabilities.coreVersion())) {
            LOG.warn("Worker {} runs Core {} but the coordinator runs Core {}; only algorithm versions that "
                    + "match are scheduled on it", workerId, capabilities.coreVersion(), CoreVersion.version());
        }
        session.setState(WorkerSession.State.REGISTERING);
        String sessionId = UUID.randomUUID().toString();
        Instant now = config.clock().instant();
        WorkerRegistry.Registration result = registry.register(workerId, sessionId, capabilities,
                session.connection().remoteAddress(), now, System.nanoTime());
        if (result instanceof WorkerRegistry.Rejected rejected) {
            session.setState(WorkerSession.State.CONNECTED);
            reject(session, register, ProtocolErrorCode.REGISTRATION_REJECTED, rejected.reason());
            return;
        }
        WorkerRegistry.Accepted accepted = (WorkerRegistry.Accepted) result;
        session.registered(workerId, sessionId);
        byWorker.put(workerId, session);
        session.connection().setMaxOutboundBytes(capabilities.maxMessageBytes());
        session.send(session.message(MessageType.REGISTER_ACK, null, register.messageId(),
                new RegisterAckPayload(sessionId, config.coordinatorId(), DistributedVersion.version(),
                        config.heartbeatInterval().toMillis(), config.heartbeatTimeout().toMillis(),
                        config.maxMessageBytes(),
                        authenticator.coordinatorProof(workerId, payload.proof(), sessionId))));
        LOG.info("Worker {} registered from {} ({} cores, {} slots, {} algorithms{})", workerId,
                session.connection().remoteAddress(), capabilities.cpuCores(), capabilities.maxConcurrentTasks(),
                capabilities.algorithms().size(), capabilities.faultSimulation() ? ", FAULT SIMULATION" : "");
        for (Listener listener : listeners) {
            listener.workerRegistered(accepted.record(), accepted.reconnection());
        }
    }

    @Override
    public void onMessage(WorkerSession session, Envelope message, long receivedNanos, int frameBytes) {
        if (session.state() != WorkerSession.State.REGISTERED) {
            // REGISTER was rejected, or the session is closing: drop silently.
            return;
        }
        if (!message.workerId().equals(session.workerId())) {
            session.protocolError(ProtocolErrorCode.INVALID_MESSAGE, "This connection is registered as "
                    + session.workerId() + ", not " + message.workerId(), message.messageId(), null, true);
            return;
        }
        WorkerRecord worker = registry.get(session.workerId()).orElseThrow();
        switch (message.type()) {
            case HEARTBEAT -> heartbeat(session, worker, message, receivedNanos);
            case SHUTDOWN -> workerShutdown(session, worker, message.payload(ShutdownPayload.class));
            case REGISTER -> session.protocolError(ProtocolErrorCode.UNEXPECTED_MESSAGE,
                    "This connection is already registered", message.messageId(), null, false);
            case TASK_ACCEPTED, TASK_PROGRESS, RESULT -> attempts.onAttemptMessage(session, worker, message,
                    receivedNanos, frameBytes);
            case ERROR -> {
                if (message.attemptRef() != null) {
                    attempts.onAttemptMessage(session, worker, message, receivedNanos, frameBytes);
                } else {
                    ErrorPayload error = message.payload(ErrorPayload.class);
                    LOG.warn("Worker {} reported {}: {}", worker.workerId(), error.code(), error.message());
                }
            }
            default -> session.protocolError(ProtocolErrorCode.UNEXPECTED_MESSAGE, message.type()
                    + " is not expected from a worker", message.messageId(), message.attemptRef(), false);
        }
    }

    @Override
    public void onClosed(WorkerSession session, CloseReason reason) {
        sessions.remove(session);
        if (session.state() == WorkerSession.State.REGISTERED) {
            disconnect(session, "connection closed (" + reason + ")");
        }
        session.setState(WorkerSession.State.CLOSED);
    }

    /**
     * Checks registration and heartbeat deadlines (control loop).
     *
     * @param nowNanos current monotonic time
     */
    void tick(long nowNanos) {
        for (WorkerSession session : new ArrayList<>(sessions)) {
            if (session.state() == WorkerSession.State.CONNECTED
                    && nowNanos - session.openedNanos() > config.registrationTimeout().toNanos()) {
                LOG.warn("{} did not register within {} ms; closing", session,
                        config.registrationTimeout().toMillis());
                session.protocolError(ProtocolErrorCode.NOT_REGISTERED, "No REGISTER within "
                        + config.registrationTimeout().toMillis() + " ms", null, null, true);
                sessions.remove(session);
            }
        }
        Instant now = config.clock().instant();
        for (WorkerRecord worker : registry.connected()) {
            long silence = nowNanos - worker.lastHeartbeatNanos();
            if (silence > config.heartbeatTimeout().toNanos()) {
                WorkerSession session = byWorker.get(worker.workerId());
                LOG.warn("Worker {}: no heartbeat for {} ms; declaring it unavailable", worker.workerId(),
                        silence / 1_000_000);
                if (session != null) {
                    disconnect(session, "heartbeat timeout (" + silence / 1_000_000 + " ms without a heartbeat)");
                    session.close();
                }
            } else if (silence > config.suspectAfter().toNanos() && worker.status() == WorkerStatus.ACTIVE) {
                changeStatus(worker, WorkerStatus.SUSPECT, "heartbeat late (" + silence / 1_000_000 + " ms)", now);
            }
        }
    }

    /**
     * Changes a worker's status and notifies listeners (control loop).
     *
     * @param worker the worker
     * @param status new status
     * @param reason why
     * @param now current time
     */
    void changeStatus(WorkerRecord worker, WorkerStatus status, String reason, Instant now) {
        WorkerStatus previous = worker.setStatus(status, reason, now);
        if (previous != null) {
            LOG.info("Worker {}: {} -> {} ({})", worker.workerId(), previous, status, reason);
            for (Listener listener : listeners) {
                listener.workerStatusChanged(worker, previous, reason);
            }
        }
    }

    /**
     * Announces shutdown to every worker and closes the connections (control loop).
     *
     * @param reason reason sent to the workers
     * @param reconnect whether workers should try to reconnect
     */
    void shutdown(String reason, boolean reconnect) {
        shuttingDown = true;
        for (WorkerSession session : new ArrayList<>(sessions)) {
            if (session.state() == WorkerSession.State.REGISTERED) {
                session.send(session.message(MessageType.SHUTDOWN, null, null,
                        new ShutdownPayload(reason, false, reconnect)));
                disconnect(session, "coordinator shutdown");
            }
            session.closeAfterFlush();
        }
        sessions.clear();
    }

    private void heartbeat(WorkerSession session, WorkerRecord worker, Envelope message, long receivedNanos) {
        HeartbeatPayload heartbeat = message.payload(HeartbeatPayload.class);
        if (!worker.heartbeat(heartbeat, config.clock().instant(), receivedNanos)) {
            LOG.debug("Worker {}: ignored stale heartbeat {}", worker.workerId(), heartbeat.sequence());
            return;
        }
        if (worker.status() == WorkerStatus.SUSPECT) {
            changeStatus(worker, WorkerStatus.ACTIVE, "heartbeat resumed", config.clock().instant());
        }
        session.send(session.message(MessageType.HEARTBEAT_ACK, null, message.messageId(),
                new HeartbeatAckPayload(heartbeat.sequence(), worker.status().name())));
        for (Listener listener : listeners) {
            listener.workerHeartbeat(worker, heartbeat);
        }
    }

    private void workerShutdown(WorkerSession session, WorkerRecord worker, ShutdownPayload shutdown) {
        LOG.info("Worker {} is shutting down ({}, drain={})", worker.workerId(), shutdown.reason(), shutdown.drain());
        if (shutdown.drain() && worker.status().isConnected()) {
            changeStatus(worker, WorkerStatus.DRAINING, "worker shutdown: " + shutdown.reason(),
                    config.clock().instant());
        } else {
            disconnect(session, "worker shutdown: " + shutdown.reason());
            session.closeAfterFlush();
        }
    }

    private void disconnect(WorkerSession session, String reason) {
        String workerId = session.workerId();
        session.setState(WorkerSession.State.CLOSED);
        if (byWorker.get(workerId) == session) {
            byWorker.remove(workerId);
        }
        List<String> assigned = registry.disconnect(workerId, reason, config.clock().instant());
        WorkerRecord worker = registry.get(workerId).orElseThrow();
        LOG.info("Worker {} disconnected: {} ({} attempts assigned)", workerId, reason, assigned.size());
        for (Listener listener : listeners) {
            listener.workerDisconnected(worker, assigned, reason);
        }
    }

    private void reject(WorkerSession session, Envelope register, ProtocolErrorCode code, String reason) {
        LOG.warn("Registration of {} rejected: {}", register.workerId(), reason);
        if (events != null) {
            events.workerRejected(register.workerId(), reason);
        }
        session.send(Envelope.builder(MessageType.ERROR).inReplyTo(register.messageId())
                .workerId(register.workerId()).payload(new ErrorPayload(code, reason, false, true, null)));
        session.closeAfterFlush();
    }

    private static void unknownAttempt(WorkerSession session, WorkerRecord worker, Envelope message,
            long receivedNanos, int frameBytes) {
        if (message.type() != MessageType.ERROR) {
            session.send(session.error(ProtocolErrorCode.UNKNOWN_TASK, "No attempt " + message.attemptId()
                    + " is known to this coordinator", message.messageId(), message.attemptRef(), false, false));
        }
    }
}
