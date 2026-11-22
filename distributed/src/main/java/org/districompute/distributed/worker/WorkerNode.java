package org.districompute.distributed.worker;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.districompute.distributed.communication.CloseReason;
import org.districompute.distributed.communication.Connection;
import org.districompute.distributed.communication.Sockets;
import org.districompute.distributed.protocol.DuplicateMessageFilter;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.protocol.MessageCodec;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.ProtocolException;
import org.districompute.distributed.protocol.ProtocolVersion;
import org.districompute.distributed.protocol.RegisterAckPayload;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.protocol.RegistrationProof;
import org.districompute.distributed.protocol.ShutdownPayload;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.resource.CapabilityProbe;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A worker process: connects to the coordinator, registers with its detected capabilities, sends heartbeats and
 * executes the attempts it is given through Core ({@code docs/worker-lifecycle.md}).
 *
 * <p>If the connection is lost, running attempts are cancelled (their results could not be delivered), and the
 * worker reconnects with exponential backoff and registers again. It stops when {@link #close()} is called, or
 * when the coordinator sends {@code SHUTDOWN} without {@code reconnect}.
 */
public final class WorkerNode implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(WorkerNode.class);

    /** Connection state of the worker. */
    public enum State {
        /** Opening a connection to the coordinator. */
        CONNECTING,
        /** Connected; waiting for {@code REGISTER_ACK}. */
        REGISTERING,
        /** Registered; executing attempts. */
        REGISTERED,
        /** Stopped for good. */
        STOPPED
    }

    private final WorkerConfig config;
    private final WorkerCapabilities capabilities;
    private final RegistrationAuthenticator authenticator;
    private final TaskExecutor executor;
    private final ScheduledExecutorService heartbeats;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicLong registrations = new AtomicLong();
    private volatile State state = State.CONNECTING;
    private volatile Link link;
    private volatile String lastError;
    private volatile Thread connector;

    private WorkerNode(WorkerConfig config) {
        this.config = config;
        this.capabilities = CapabilityProbe.probe(config.registry(), config.allowedAlgorithms(),
                WorkerConfig.hostName(), config.maxConcurrentTasks(), config.maxTaskMemoryBytes(),
                config.maxMessageBytes(), config.labels(), false);
        this.authenticator = new RegistrationAuthenticator(config.secret());
        this.executor = new TaskExecutor(config);
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("worker-heartbeat").daemon(true).unstarted(runnable));
    }

    /**
     * Starts a worker. It connects in the background; see {@link #awaitRegistered}.
     *
     * @param config the settings
     * @return the running worker
     */
    public static WorkerNode start(WorkerConfig config) {
        WorkerNode node = new WorkerNode(config);
        node.connector = Thread.ofPlatform().name("worker-connector").daemon(true).start(node::connectLoop);
        return node;
    }

    /**
     * Waits until the worker is registered.
     *
     * @param timeout how long to wait
     * @return {@code true} if registered within the timeout
     */
    public boolean awaitRegistered(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (state != State.REGISTERED) {
            if (state == State.STOPPED || System.nanoTime() > deadline) {
                return false;
            }
            sleep(Duration.ofMillis(10));
        }
        return true;
    }

    /**
     * Waits until the worker has stopped (after {@link #close()} or a final {@code SHUTDOWN}).
     *
     * @param timeout how long to wait
     * @return {@code true} if stopped in time
     */
    public boolean awaitStopped(Duration timeout) {
        try {
            return stopped.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Current state.
     *
     * @return the state
     */
    public State state() {
        return state;
    }

    /**
     * The worker's configuration.
     *
     * @return the configuration
     */
    public WorkerConfig config() {
        return config;
    }

    /**
     * Capabilities reported at registration.
     *
     * @return the capabilities
     */
    public WorkerCapabilities capabilities() {
        return capabilities;
    }

    /**
     * Number of accepted registrations (more than one after reconnections).
     *
     * @return the count
     */
    public long registrations() {
        return registrations.get();
    }

    /**
     * The last registration or connection problem, for diagnostics.
     *
     * @return a message, or {@code null}
     */
    public String lastError() {
        return lastError;
    }

    /**
     * Attempts currently executing.
     *
     * @return the count
     */
    public int activeTasks() {
        return executor.active();
    }

    /**
     * Attempts completed since the worker started.
     *
     * @return the count
     */
    public long completedTasks() {
        return executor.completed();
    }

    /**
     * Changes the concurrency limit at run time. The coordinator learns it from the next heartbeat.
     *
     * @param tasks attempts at the same time
     */
    public void setMaxConcurrentTasks(int tasks) {
        executor.setCapacity(tasks);
        LOG.info("Worker {}: concurrency limit is now {}", config.workerId(), tasks);
    }

    /**
     * Stops gracefully: announces {@code SHUTDOWN} with {@code drain}, lets running attempts finish and deliver
     * their results (up to the drain timeout), then disconnects.
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            awaitStopped(config.drainTimeout().plusSeconds(5));
            return;
        }
        executor.stopAccepting();
        Link current = link;
        if (current != null && state == State.REGISTERED) {
            current.send(Envelope.builder(MessageType.SHUTDOWN).workerId(config.workerId())
                    .payload(new ShutdownPayload("worker " + config.workerId() + " stopped", true, false)));
            if (!executor.awaitIdle(config.drainTimeout())) {
                LOG.warn("Worker {}: attempts still running after the drain timeout; cancelling them",
                        config.workerId());
            }
            current.connection.closeAfterFlush(CloseReason.LOCAL);
            // Wait for the queued results and the close marker to be written before stopping the threads.
            current.awaitClosed(Duration.ofSeconds(5));
        } else if (current != null) {
            current.connection.close(CloseReason.LOCAL);
        }
        finish();
    }

    /**
     * Stops immediately, as a crash would: no {@code SHUTDOWN}, running attempts abandoned. For fault experiments
     * and tests.
     */
    public void abort() {
        running.set(false);
        Link current = link;
        if (current != null) {
            current.connection.close(CloseReason.LOCAL);
        }
        finish();
    }

    private void finish() {
        Thread thread = connector;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        executor.close();
        heartbeats.shutdownNow();
        state = State.STOPPED;
        stopped.countDown();
    }

    private void connectLoop() {
        long backoff = config.initialBackoff().toMillis();
        while (running.get()) {
            state = State.CONNECTING;
            Link current = null;
            try {
                Socket socket = Sockets.connect(config.tls(), config.coordinatorHost(), config.coordinatorPort(),
                        (int) config.connectTimeout().toMillis());
                current = new Link(socket);
                link = current;
                state = State.REGISTERING;
                if (current.register()) {
                    registrations.incrementAndGet();
                    state = State.REGISTERED;
                    backoff = config.initialBackoff().toMillis();
                    lastError = null;
                    current.startHeartbeats();
                    current.awaitClosed();
                }
            } catch (IOException e) {
                lastError = "Cannot reach coordinator " + config.coordinatorHost() + ":" + config.coordinatorPort()
                        + ": " + e.getMessage();
                LOG.warn("Worker {}: {}", config.workerId(), lastError);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                if (current != null) {
                    current.stopHeartbeats();
                    current.connection.close(CloseReason.LOCAL);
                }
                if (running.get()) {
                    executor.cancelAll("connection to the coordinator lost");
                }
            }
            if (current != null && current.stopRequested) {
                LOG.info("Worker {}: the coordinator asked it to stop", config.workerId());
                running.set(false);
                finish();
                return;
            }
            if (!running.get()) {
                break;
            }
            long delay = backoff + ThreadLocalRandom.current().nextLong(backoff / 4 + 1);
            LOG.info("Worker {}: reconnecting in {} ms", config.workerId(), delay);
            if (!sleep(Duration.ofMillis(delay))) {
                break;
            }
            backoff = Math.min(backoff * 2, config.maxBackoff().toMillis());
        }
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** One connection to the coordinator. */
    private final class Link implements Connection.Listener, TaskExecutor.Outbox {
        private final Connection connection;
        private final DuplicateMessageFilter duplicates = new DuplicateMessageFilter(4096);
        private final CountDownLatch acknowledged = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicLong heartbeatSequence = new AtomicLong();
        private volatile RegistrationProof proof;
        private volatile boolean registered;
        private volatile boolean stopRequested;
        private volatile long heartbeatTimeoutNanos;
        private volatile long heartbeatIntervalMillis = 1000;
        private volatile long lastReceivedNanos = System.nanoTime();
        private volatile ScheduledFuture<?> heartbeatTask;

        Link(Socket socket) throws IOException {
            this.connection = Connection.open(socket, "coordinator-link", config.maxMessageBytes(), 1 << 20, 1024,
                    this);
            connection.start();
        }

        boolean register() throws InterruptedException {
            proof = authenticator.prove(ProtocolVersion.CURRENT, config.workerId());
            send(Envelope.builder(MessageType.REGISTER).workerId(config.workerId())
                    .payload(new RegisterPayload(capabilities, proof)));
            if (!acknowledged.await(config.registrationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                lastError = "No REGISTER_ACK within " + config.registrationTimeout().toMillis() + " ms";
                LOG.warn("Worker {}: {}", config.workerId(), lastError);
                return false;
            }
            return registered;
        }

        void startHeartbeats() {
            long interval = Math.max(10, heartbeatIntervalMillis);
            heartbeatTask = heartbeats.scheduleAtFixedRate(this::heartbeat, interval, interval,
                    TimeUnit.MILLISECONDS);
        }

        void stopHeartbeats() {
            ScheduledFuture<?> task = heartbeatTask;
            if (task != null) {
                task.cancel(false);
            }
        }

        void awaitClosed() throws InterruptedException {
            closed.await();
        }

        boolean awaitClosed(Duration timeout) {
            try {
                return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void heartbeat() {
            if (!connection.isOpen()) {
                return;
            }
            long silence = System.nanoTime() - lastReceivedNanos;
            if (heartbeatTimeoutNanos > 0 && silence > heartbeatTimeoutNanos) {
                lastError = "The coordinator has been silent for " + silence / 1_000_000 + " ms";
                LOG.warn("Worker {}: {}; reconnecting", config.workerId(), lastError);
                connection.close(CloseReason.LOCAL);
                return;
            }
            send(Envelope.builder(MessageType.HEARTBEAT).workerId(config.workerId())
                    .payload(new HeartbeatPayload(heartbeatSequence.incrementAndGet(), executor.active(),
                            executor.capacity(), CapabilityProbe.freeHeap(), CapabilityProbe.systemLoadAverage(),
                            CapabilityProbe.processCpuLoad(), executor.completed(), executor.failed(),
                            executor.runningAttempts())));
        }

        @Override
        public boolean send(Envelope message) {
            byte[] frame = MessageCodec.encode(message);
            if (frame.length > connection.maxOutboundBytes() && message.type() == MessageType.RESULT) {
                String text = "The result of " + message.attemptId() + " needs " + frame.length
                        + " bytes but the coordinator accepts at most " + connection.maxOutboundBytes();
                LOG.error("Worker {}: {}", config.workerId(), text);
                return send(Envelope.builder(MessageType.ERROR).workerId(config.workerId())
                        .attempt(message.attemptRef()).payload(new ErrorPayload(ProtocolErrorCode.TASK_FAILED, text,
                                false, false, null)));
            }
            try {
                return connection.send(frame);
            } catch (IllegalArgumentException e) {
                LOG.error("Worker {}: cannot send {}: {}", config.workerId(), message.type(), e.getMessage());
                return false;
            }
        }

        @Override
        public boolean isOpen() {
            return connection.isOpen();
        }

        @Override
        public void onFrame(Connection source, byte[] frame, long receivedNanos) {
            lastReceivedNanos = receivedNanos;
            Envelope message;
            try {
                message = MessageCodec.decode(frame);
            } catch (ProtocolException e) {
                LOG.warn("Worker {}: invalid message from the coordinator: {}", config.workerId(), e.getMessage());
                send(Envelope.builder(MessageType.ERROR).workerId(config.workerId()).inReplyTo(e.messageId())
                        .payload(new ErrorPayload(e.code(), e.getMessage(), false, e.fatal(), null)));
                if (e.fatal()) {
                    connection.closeAfterFlush(CloseReason.LOCAL);
                }
                return;
            }
            if (!duplicates.firstSeen(message.messageId())) {
                LOG.warn("Worker {}: dropped duplicate message {}", config.workerId(), message.messageId());
                return;
            }
            if (!message.type().canBeSentBy(MessageType.Role.COORDINATOR)) {
                send(Envelope.builder(MessageType.ERROR).workerId(config.workerId()).inReplyTo(message.messageId())
                        .payload(new ErrorPayload(ProtocolErrorCode.UNEXPECTED_MESSAGE, message.type()
                                + " is not sent by a coordinator", false, false, null)));
                return;
            }
            if (!registered) {
                beforeRegistration(message);
            } else {
                registeredMessage(message, receivedNanos);
            }
        }

        private void beforeRegistration(Envelope message) {
            switch (message.type()) {
                case REGISTER_ACK -> {
                    RegisterAckPayload ack = message.payload(RegisterAckPayload.class);
                    if (!authenticator.verifyCoordinator(config.workerId(), proof, ack.sessionId(),
                            ack.coordinatorProof())) {
                        lastError = "The coordinator could not prove the shared secret; refusing to work for it";
                        LOG.error("Worker {}: {}", config.workerId(), lastError);
                        connection.close(CloseReason.LOCAL);
                        acknowledged.countDown();
                        return;
                    }
                    heartbeatIntervalMillis = ack.heartbeatIntervalMillis();
                    heartbeatTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(ack.heartbeatTimeoutMillis());
                    connection.setMaxOutboundBytes(ack.maxMessageBytes());
                    registered = true;
                    LOG.info("Worker {} registered with {} (session {})", config.workerId(), ack.coordinatorId(),
                            ack.sessionId());
                    acknowledged.countDown();
                }
                case ERROR -> {
                    ErrorPayload error = message.payload(ErrorPayload.class);
                    lastError = error.code() + ": " + error.message();
                    LOG.error("Worker {}: registration refused: {}", config.workerId(), lastError);
                    acknowledged.countDown();
                }
                case SHUTDOWN -> shutdown(message.payload(ShutdownPayload.class));
                default -> LOG.warn("Worker {}: ignored {} before registration", config.workerId(), message.type());
            }
        }

        private void registeredMessage(Envelope message, long receivedNanos) {
            switch (message.type()) {
                case TASK -> executor.submit(message, receivedNanos, this);
                case CANCEL -> {
                    if (!executor.cancel(message.attemptId()) && !executor.recentlyFinished(message.attemptId())) {
                        send(Envelope.builder(MessageType.ERROR).workerId(config.workerId())
                                .attempt(message.attemptRef()).inReplyTo(message.messageId())
                                .payload(new ErrorPayload(ProtocolErrorCode.UNKNOWN_TASK, "No attempt "
                                        + message.attemptId() + " on worker " + config.workerId(), false, false,
                                        null)));
                    }
                }
                case HEARTBEAT_ACK -> {
                    // lastReceivedNanos is already updated; the status is informational.
                }
                case SHUTDOWN -> shutdown(message.payload(ShutdownPayload.class));
                case ERROR -> {
                    ErrorPayload error = message.payload(ErrorPayload.class);
                    LOG.warn("Worker {}: coordinator reported {} {}: {}", config.workerId(), error.code(),
                            message.attemptId() == null ? "" : "for " + message.attemptId(), error.message());
                }
                default -> LOG.warn("Worker {}: ignored {}", config.workerId(), message.type());
            }
        }

        private void shutdown(ShutdownPayload shutdown) {
            LOG.info("Worker {}: coordinator is shutting down ({}); {}", config.workerId(), shutdown.reason(),
                    shutdown.reconnect() ? "will reconnect" : "stopping");
            stopRequested = !shutdown.reconnect();
            connection.close(CloseReason.LOCAL);
        }

        @Override
        public void onFrameTooLarge(Connection source, int limit) {
            send(Envelope.builder(MessageType.ERROR).workerId(config.workerId()).payload(new ErrorPayload(
                    ProtocolErrorCode.MESSAGE_TOO_LARGE, "Frames are limited to " + limit + " bytes", false, true,
                    null)));
        }

        @Override
        public void onClosed(Connection source, CloseReason reason, Throwable cause) {
            if (registered) {
                LOG.info("Worker {}: connection to the coordinator closed ({})", config.workerId(), reason);
            }
            acknowledged.countDown();
            closed.countDown();
        }
    }
}
