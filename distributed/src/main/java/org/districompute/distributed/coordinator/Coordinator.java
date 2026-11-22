package org.districompute.distributed.coordinator;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.districompute.core.DistriComputeException;
import org.districompute.core.task.TaskSpec;
import org.districompute.distributed.communication.Connection;
import org.districompute.distributed.communication.Sockets;
import org.districompute.distributed.metrics.CoordinatorMetrics;
import org.districompute.distributed.monitoring.EventBus;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.scheduler.Policies;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.scheduler.Scheduler;
import org.districompute.distributed.security.RegistrationAuthenticator;
import org.districompute.distributed.task.DistributedTask;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskSnapshot;
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
    private final Scheduler scheduler;
    private final TaskManager tasks;
    private final EventBus eventBus;
    private final RuntimeEvents events;
    private final Instant startedAt;
    private final AtomicBoolean closed = new AtomicBoolean();

    private Coordinator(CoordinatorConfig config) throws IOException {
        this.config = config;
        this.authenticator = new RegistrationAuthenticator(config.secret(), config.maxClockSkew(), config.clock());
        this.loop = new ControlLoop("coordinator-loop", 100_000);
        this.startedAt = config.clock().instant();
        this.eventBus = new EventBus(10_000, 10_000, config.clock());
        this.events = new RuntimeEvents(eventBus);
        this.workers = new WorkerManager(config, authenticator);
        workers.setEvents(events);
        this.scheduler = new Scheduler(config.tasks().policy(), Policies.builtIn());
        this.tasks = new TaskManager(config, loop, workers, scheduler, events);
        workers.addListener(tasks);
        workers.setAttemptMessages(tasks);
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
        ticker.scheduleAtFixedRate(() -> loop.execute(() -> {
            long now = System.nanoTime();
            workers.tick(now);
            tasks.tick(now);
        }), tick, tick, TimeUnit.MILLISECONDS);
        Thread.ofPlatform().name("coordinator-accept").daemon(true).start(this::acceptLoop);
        events.coordinator(EventType.COORDINATOR_STARTED, config.coordinatorId(), "coordinator "
                + config.coordinatorId() + " listening on port " + port(), Map.of("port", port(),
                "tls", config.tls().enabled(), "policy", config.tasks().policy().name()));
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
     * Submits a task with the coordinator's default options.
     *
     * @param spec Core task specification (mode {@code DISTRIBUTED_CPU})
     * @return a handle to wait for, inspect or cancel the task
     * @throws DistriComputeException if the task is refused (unknown algorithm, unsupported mode, too many tasks)
     * @throws IllegalArgumentException if the task id is already in use
     */
    public TaskHandle submit(TaskSpec spec) {
        return submit(spec, SubmitOptions.DEFAULTS);
    }

    /**
     * Submits a task. Validation, planning and dispatch happen asynchronously; problems found then end the task
     * with a {@code FAILED} result instead of an exception.
     *
     * @param spec Core task specification (mode {@code DISTRIBUTED_CPU})
     * @param options runtime options
     * @return a handle to wait for, inspect or cancel the task
     * @throws DistriComputeException if the task is refused (unknown algorithm, unsupported mode, too many tasks)
     * @throws IllegalArgumentException if the task id is already in use or an option is invalid
     */
    public TaskHandle submit(TaskSpec spec, SubmitOptions options) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(options, "options");
        DistributedTask task = loop.call(() -> tasks.submit(spec, options), QUERY_TIMEOUT);
        return new TaskHandle(spec.taskId(), task.completion(), this);
    }

    /**
     * A task's current state.
     *
     * @param taskId the task id
     * @return the snapshot, if the task is active or retained
     */
    public Optional<TaskSnapshot> task(String taskId) {
        return loop.call(() -> tasks.snapshot(taskId), QUERY_TIMEOUT);
    }

    /**
     * All active and retained tasks.
     *
     * @return snapshots, finished tasks first
     */
    public List<TaskSnapshot> tasks() {
        return loop.call(tasks::snapshots, QUERY_TIMEOUT);
    }

    /**
     * Cancels a task. Running attempts are cancelled on their workers.
     *
     * @param taskId the task id
     * @return {@code true} if the task was running and is now cancelled
     */
    public boolean cancel(String taskId) {
        return loop.call(() -> tasks.cancel(taskId, "cancelled by the client"), QUERY_TIMEOUT);
    }

    /**
     * The default scheduling policy.
     *
     * @return the policy
     */
    public PolicyName schedulingPolicy() {
        return loop.call(scheduler::defaultPolicy, QUERY_TIMEOUT);
    }

    /**
     * The scheduling policies this coordinator offers.
     *
     * @return their names
     */
    public List<PolicyName> availablePolicies() {
        return loop.call(scheduler::available, QUERY_TIMEOUT);
    }

    /**
     * Changes the default scheduling policy for tasks submitted from now on.
     *
     * @param policy the policy
     * @throws IllegalArgumentException if it is not available
     */
    public void setSchedulingPolicy(PolicyName policy) {
        loop.call(() -> {
            PolicyName before = scheduler.defaultPolicy();
            scheduler.setDefaultPolicy(policy);
            if (before != policy) {
                events.policyChanged(before, policy);
            }
            return null;
        }, QUERY_TIMEOUT);
    }

    /**
     * The runtime's event stream: subscribe, or read recent events.
     *
     * @return the event bus
     */
    public EventBus events() {
        return eventBus;
    }

    /**
     * Coordinator-wide figures.
     *
     * @return the metrics
     */
    public CoordinatorMetrics metrics() {
        return loop.call(() -> {
            Map<String, Integer> byStatus = new java.util.TreeMap<>();
            int totalSlots = 0;
            int busySlots = 0;
            for (WorkerRecord worker : workers.registry().all()) {
                byStatus.merge(worker.status().name(), 1, Integer::sum);
                if (worker.status().isConnected()) {
                    busySlots += worker.assignedAttempts().size();
                    if (worker.status() == WorkerStatus.ACTIVE) {
                        totalSlots += worker.maxConcurrentTasks();
                    }
                }
            }
            return new CoordinatorMetrics(config.coordinatorId(), startedAt,
                    java.time.Duration.between(startedAt, config.clock().instant()).toMillis(), byStatus, totalSlots,
                    busySlots, tasks.activeTasks(), tasks.pendingPartitions(),
                    tasks.counter(TaskManager.Counter.TASKS_SUBMITTED),
                    tasks.counter(TaskManager.Counter.TASKS_COMPLETED), tasks.counter(TaskManager.Counter.TASKS_FAILED),
                    tasks.counter(TaskManager.Counter.TASKS_CANCELLED),
                    tasks.counter(TaskManager.Counter.ATTEMPTS_DISPATCHED),
                    tasks.counter(TaskManager.Counter.ATTEMPTS_SUCCEEDED),
                    tasks.counter(TaskManager.Counter.ATTEMPTS_FAILED),
                    tasks.counter(TaskManager.Counter.PARTITIONS_RETRIED),
                    tasks.counter(TaskManager.Counter.BYTES_SENT), tasks.counter(TaskManager.Counter.BYTES_RECEIVED),
                    eventBus.lastSequence());
        }, QUERY_TIMEOUT);
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
        events.coordinator(EventType.COORDINATOR_STOPPING, config.coordinatorId(), "coordinator "
                + config.coordinatorId() + " shutting down", Map.of());
        try {
            server.close();
        } catch (IOException e) {
            LOG.debug("Error closing the server socket", e);
        }
        ticker.shutdownNow();
        try {
            loop.call(() -> {
                tasks.shutdown();
                workers.shutdown("coordinator " + config.coordinatorId() + " is shutting down", true);
                return null;
            }, QUERY_TIMEOUT);
        } catch (IllegalStateException e) {
            LOG.warn("Could not notify workers of the shutdown: {}", e.getMessage());
        }
        loop.close();
        tasks.close();
        eventBus.close();
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
