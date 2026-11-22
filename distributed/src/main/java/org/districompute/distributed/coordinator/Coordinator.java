package org.districompute.distributed.coordinator;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.districompute.distributed.communication.Connection;
import org.districompute.distributed.communication.Sockets;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The coordinator: accepts worker connections, keeps the worker registry and tracks worker health. Start one with
 * {@link #start(CoordinatorConfig)}; it runs until {@link #close()}.
 *
 * <p>All public methods are thread-safe. Queries return immutable snapshots taken on the control loop.
 */
public final class Coordinator implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Coordinator.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(10);

    private final CoordinatorConfig config;
    private final ControlLoop loop;
    private final WorkerManager workers;
    private final RegistrationAuthenticator authenticator;
    private final ServerSocket server;
    private final ScheduledExecutorService ticker;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Coordinator(CoordinatorConfig config) throws IOException {
        this.config = config;
        this.authenticator = new RegistrationAuthenticator(config.secret(), config.maxClockSkew(), config.clock());
        this.loop = new ControlLoop("coordinator-loop", 100_000);
        this.workers = new WorkerManager(config, authenticator);
        try {
            this.server = Sockets.listen(config.tls(), config.bindAddress(), config.port(), 128);
        } catch (IOException e) {
            loop.close();
            throw e;
        }
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform().name("coordinator-ticker").daemon(true).unstarted(runnable));
    }

    /**
     * Starts a coordinator: binds the worker port and begins accepting workers.
     *
     * @param config the settings
     * @return the running coordinator
     * @throws IOException if the port cannot be bound or TLS cannot be initialized
     */
    public static Coordinator start(CoordinatorConfig config) throws IOException {
        Coordinator coordinator = new Coordinator(config);
        coordinator.begin();
        return coordinator;
    }

    private void begin() {
        long tick = config.tickInterval().toMillis();
        ticker.scheduleAtFixedRate(() -> loop.execute(() -> workers.tick(System.nanoTime())), tick, tick,
                TimeUnit.MILLISECONDS);
        Thread.ofPlatform().name("coordinator-accept").daemon(true).start(this::acceptLoop);
        LOG.info("Coordinator {} listening for workers on {}:{}{}", config.coordinatorId(), config.bindAddress(),
                port(), config.tls().enabled() ? " (TLS)" : "");
    }

    /**
     * The bound worker port (useful when the configuration asked for an ephemeral port).
     *
     * @return the port
     */
    public int port() {
        return server.getLocalPort();
    }

    /**
     * The coordinator's settings.
     *
     * @return the configuration
     */
    public CoordinatorConfig config() {
        return config;
    }

    /**
     * All known workers, connected or recently disconnected.
     *
     * @return snapshots in registration order
     */
    public List<WorkerSnapshot> workers() {
        return loop.call(() -> workers.registry().snapshots(), QUERY_TIMEOUT);
    }

    /**
     * One worker.
     *
     * @param workerId worker id
     * @return its snapshot, if known
     */
    public Optional<WorkerSnapshot> worker(String workerId) {
        return loop.call(() -> workers.registry().get(workerId).map(WorkerRecord::snapshot), QUERY_TIMEOUT);
    }

    /**
     * Stops accepting workers, sends {@code SHUTDOWN} (with {@code reconnect: true}, so workers come back when a
     * coordinator restarts on the same address) and closes all connections.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Coordinator {} shutting down", config.coordinatorId());
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("Error closing the server socket", e);
        }
        ticker.shutdownNow();
        try {
            loop.call(() -> {
                workers.shutdown("coordinator " + config.coordinatorId() + " is shutting down", true);
                return null;
            }, QUERY_TIMEOUT);
        } catch (IllegalStateException e) {
            LOG.warn("Could not notify workers of the shutdown: {}", e.getMessage());
        }
        loop.close();
    }

    ControlLoop loop() {
        return loop;
    }

    WorkerManager workerManager() {
        return workers;
    }

    private void acceptLoop() {
        while (!closed.get()) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (SocketException e) {
                if (!closed.get()) {
                    LOG.error("Accepting worker connections failed", e);
                }
                return;
            } catch (IOException e) {
                LOG.warn("Accepting a worker connection failed: {}", e.getMessage());
                continue;
            }
            try {
                Sockets.configure(socket);
                WorkerSession session = new WorkerSession(authenticator, config.maxProtocolErrors(),
                        loop::execute, workers);
                Connection connection = Connection.open(socket, "worker-conn-" + socket.getPort(),
                        config.maxMessageBytes(), 1 << 20, config.outboundQueueCapacity(), session);
                session.attach(connection);
                loop.execute(() -> workers.accepted(session));
                connection.start();
            } catch (IOException e) {
                LOG.warn("Could not set up the connection from {}: {}", socket.getRemoteSocketAddress(),
                        e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {
                    LOG.debug("Error closing a failed connection", ignored);
                }
            }
        }
    }
}
