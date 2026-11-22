package org.districompute.distributed.monitoring;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.districompute.core.CoreVersion;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.validation.ValidationException;
import org.districompute.distributed.DistributedVersion;
import org.districompute.distributed.Json;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.protocol.ProtocolVersion;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * HTTP API of a standalone coordinator for the Application and for operators ({@code docs/integration-contract.md},
 * "HTTP API"): JSON resources under {@code /api/v1} and a Server-Sent Events stream of runtime events. It uses the
 * JDK's built-in HTTP server, so it adds no dependency, and binds to {@code 127.0.0.1} unless told otherwise.
 */
public final class MonitoringServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MonitoringServer.class);
    private static final String PREFIX = "/api/v1";
    private static final int MAX_BODY_BYTES = 16 << 20;
    private static final long KEEP_ALIVE_MILLIS = 15_000;

    /**
     * Server settings.
     *
     * @param bindAddress address to bind (default {@code 127.0.0.1})
     * @param port port (0 for an ephemeral port)
     * @param token bearer token required on every request, or {@code null} for none (only on trusted hosts)
     * @param corsOrigin value of {@code Access-Control-Allow-Origin}, or {@code null} for no CORS headers
     * @param maxStreams concurrent event streams
     */
    public record Settings(String bindAddress, int port, String token, String corsOrigin, int maxStreams) {

        /** Validates the settings. */
        public Settings {
            Objects.requireNonNull(bindAddress, "bindAddress");
            if (port < 0 || port > 65_535 || maxStreams < 1) {
                throw new IllegalArgumentException("Invalid port or maxStreams");
            }
            if (token != null && token.length() < 16) {
                throw new IllegalArgumentException("The HTTP token must have at least 16 characters");
            }
        }

        @Override
        public String toString() {
            return "Settings[bind=" + bindAddress + ", port=" + port + ", token=" + (token == null ? "none" : "set")
                    + ", corsOrigin=" + corsOrigin + ", maxStreams=" + maxStreams + "]";
        }
    }

    /**
     * Body of {@code POST /api/v1/tasks}: Core's task specification plus runtime options.
     *
     * @param task Core {@code TaskSpec} document
     * @param policy scheduling policy, or {@code null}
     * @param partitionsPerSlot partitions per free slot, or {@code null}
     * @param attemptTimeoutMillis attempt timeout, or {@code null}
     * @param maxAttempts attempts per partition, or {@code null}
     * @param maxWorkers distinct worker limit, or {@code null}
     */
    public record SubmitRequest(JsonNode task, PolicyName policy, Integer partitionsPerSlot, Long attemptTimeoutMillis,
            Integer maxAttempts, Integer maxWorkers) {
    }

    private final Coordinator coordinator;
    private final Settings settings;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger streams = new AtomicInteger();

    private MonitoringServer(Coordinator coordinator, Settings settings) throws IOException {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.settings = settings;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(settings.bindAddress()),
                settings.port()), 64);
        server.setExecutor(executor);
        server.createContext(PREFIX, this::handle);
    }

    /**
     * Starts the HTTP API.
     *
     * @param coordinator the coordinator to expose
     * @param settings the settings
     * @return the running server
     * @throws IOException if the port cannot be bound
     */
    public static MonitoringServer start(Coordinator coordinator, Settings settings) throws IOException {
        MonitoringServer monitoring = new MonitoringServer(coordinator, settings);
        monitoring.server.start();
        LOG.info("HTTP API listening on http://{}:{}{} ({})", settings.bindAddress(), monitoring.port(), PREFIX,
                settings.token() == null ? "no token" : "bearer token required");
        return monitoring;
    }

    /**
     * The bound port.
     *
     * @return the port
     */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            cors(exchange.getResponseHeaders());
            if ("OPTIONS".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            if (!authorized(exchange)) {
                exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer");
                error(exchange, 401, "UNAUTHORIZED", "A valid bearer token is required");
                return;
            }
            route(exchange);
        } catch (RuntimeException e) {
            LOG.error("HTTP request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI(), e);
            try {
                error(exchange, 500, "INTERNAL_ERROR", "Internal error; see the coordinator's log");
            } catch (IOException | RuntimeException ignored) {
                LOG.debug("Could not report the error to the client", ignored);
            }
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath().substring(PREFIX.length());
        String[] parts = path.replaceAll("^/+|/+$", "").split("/");
        String resource = parts[0];
        switch (resource) {
            case "health" -> requireMethod(exchange, method, "GET", () -> json(exchange, 200, health()));
            case "workers" -> workers(exchange, method, parts);
            case "tasks" -> tasks(exchange, method, parts);
            case "metrics" -> requireMethod(exchange, method, "GET", () -> json(exchange, 200,
                    coordinator.metrics()));
            case "scheduling" -> scheduling(exchange, method);
            case "catalog" -> requireMethod(exchange, method, "GET", () -> json(exchange, 200, catalog()));
            case "events" -> events(exchange, method, parts);
            default -> error(exchange, 404, "NOT_FOUND", "No resource " + path);
        }
    }

    private Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("coordinatorId", coordinator.config().coordinatorId());
        health.put("protocolVersion", ProtocolVersion.CURRENT);
        health.put("runtimeVersion", DistributedVersion.version());
        health.put("coreVersion", CoreVersion.version());
        health.put("workerPort", coordinator.port());
        health.put("workersConnected", coordinator.workers().stream().filter(w -> w.status().isConnected()).count());
        return health;
    }

    private void workers(HttpExchange exchange, String method, String[] parts) throws IOException {
        if (!"GET".equals(method)) {
            methodNotAllowed(exchange);
        } else if (parts.length == 1) {
            json(exchange, 200, coordinator.workers());
        } else {
            Optional<WorkerSnapshot> worker = coordinator.worker(parts[1]);
            if (worker.isPresent()) {
                json(exchange, 200, worker.get());
            } else {
                error(exchange, 404, "NOT_FOUND", "No worker " + parts[1]);
            }
        }
    }

    private void tasks(HttpExchange exchange, String method, String[] parts) throws IOException {
        if (parts.length == 1 && "GET".equals(method)) {
            json(exchange, 200, coordinator.tasks().stream().map(TaskSummary::of).toList());
        } else if (parts.length == 1 && "POST".equals(method)) {
            submit(exchange);
        } else if (parts.length == 2 && "GET".equals(method)) {
            var task = coordinator.task(parts[1]);
            if (task.isPresent()) {
                json(exchange, 200, task.get());
            } else {
                error(exchange, 404, "NOT_FOUND", "No task " + parts[1]);
            }
        } else if (parts.length == 3 && "cancel".equals(parts[2]) && "POST".equals(method)) {
            if (coordinator.task(parts[1]).isEmpty()) {
                error(exchange, 404, "NOT_FOUND", "No task " + parts[1]);
            } else {
                json(exchange, 200, Map.of("taskId", parts[1], "cancelled", coordinator.cancel(parts[1])));
            }
        } else {
            methodNotAllowed(exchange);
        }
    }

    private void submit(HttpExchange exchange) throws IOException {
        SubmitRequest request;
        TaskSpec spec;
        try {
            request = Json.fromJson(readBody(exchange), SubmitRequest.class);
            if (request.task() == null || !request.task().isObject()) {
                error(exchange, 400, "VALIDATION_FAILED", "The body needs a \"task\" object (a Core TaskSpec)");
                return;
            }
            spec = ContractJson.fromJson(Json.toJson(request.task()), TaskSpec.class);
        } catch (ValidationException e) {
            json(exchange, 400, Map.of("error", Map.of("code", "VALIDATION_FAILED", "message", e.getMessage(),
                    "violations", e.violations())));
            return;
        } catch (DistriComputeException e) {
            error(exchange, 400, e.code().name(), e.getMessage());
            return;
        } catch (JacksonException | IllegalArgumentException e) {
            error(exchange, 400, "VALIDATION_FAILED", "Invalid request: " + e.getMessage());
            return;
        }
        try {
            SubmitOptions options = new SubmitOptions(request.policy(), request.partitionsPerSlot(),
                    request.attemptTimeoutMillis() == null ? null : Duration.ofMillis(request.attemptTimeoutMillis()),
                    request.maxAttempts(), request.maxWorkers());
            coordinator.submit(spec, options);
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("taskId", spec.taskId());
            if (spec.experimentId() != null) {
                accepted.put("experimentId", spec.experimentId());
            }
            accepted.put("location", PREFIX + "/tasks/" + spec.taskId());
            exchange.getResponseHeaders().add("Location", PREFIX + "/tasks/" + spec.taskId());
            json(exchange, 202, accepted);
        } catch (DistriComputeException e) {
            int status = e.code() == ErrorCode.RESOURCE_EXHAUSTED ? 503 : 400;
            error(exchange, status, e.code().name(), e.getMessage());
        } catch (IllegalArgumentException e) {
            error(exchange, 409, "CONFLICT", e.getMessage());
        }
    }

    private void scheduling(HttpExchange exchange, String method) throws IOException {
        if ("GET".equals(method)) {
            json(exchange, 200, schedulingState());
        } else if ("PUT".equals(method)) {
            PolicyName policy;
            try {
                JsonNode body = Json.readTree(readBody(exchange));
                policy = PolicyName.valueOf(body.path("policy").stringValue(""));
            } catch (JacksonException | IllegalArgumentException e) {
                error(exchange, 400, "VALIDATION_FAILED", "Body must be {\"policy\": one of "
                        + List.of(PolicyName.values()) + "}");
                return;
            }
            coordinator.setSchedulingPolicy(policy);
            json(exchange, 200, schedulingState());
        } else {
            methodNotAllowed(exchange);
        }
    }

    private Map<String, Object> schedulingState() {
        return Map.of("policy", coordinator.schedulingPolicy(), "available", coordinator.availablePolicies());
    }

    private List<Map<String, Object>> catalog() {
        List<WorkerSnapshot> workers = coordinator.workers();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AlgorithmInfo info : coordinator.config().registry().catalog()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("descriptor", info.descriptor());
            entry.put("capabilities", info.capabilities());
            entry.put("workers", workers.stream().filter(w -> w.status().isConnected())
                    .filter(w -> w.capabilities().algorithm(info.descriptor().id()).isPresent())
                    .map(WorkerSnapshot::workerId).toList());
            entries.add(entry);
        }
        return entries;
    }

    private void events(HttpExchange exchange, String method, String[] parts) throws IOException {
        if (!"GET".equals(method)) {
            methodNotAllowed(exchange);
            return;
        }
        Map<String, String> query = query(exchange.getRequestURI());
        long after;
        int limit;
        try {
            after = Long.parseLong(query.getOrDefault("after",
                    Optional.ofNullable(exchange.getRequestHeaders().getFirst("Last-Event-ID")).orElse("0")));
            limit = Math.min(10_000, Integer.parseInt(query.getOrDefault("limit", "1000")));
        } catch (NumberFormatException e) {
            error(exchange, 400, "VALIDATION_FAILED", "after and limit must be integers");
            return;
        }
        if (parts.length == 1) {
            json(exchange, 200, coordinator.events().recent(after, limit));
        } else if (parts.length == 2 && "stream".equals(parts[1])) {
            stream(exchange, after);
        } else {
            error(exchange, 404, "NOT_FOUND", "No resource " + String.join("/", parts));
        }
    }

    private void stream(HttpExchange exchange, long after) throws IOException {
        if (streams.incrementAndGet() > settings.maxStreams()) {
            streams.decrementAndGet();
            error(exchange, 503, "TOO_MANY_STREAMS", "At most " + settings.maxStreams() + " event streams");
            return;
        }
        try {
            Headers headers = exchange.getResponseHeaders();
            headers.add("Content-Type", "text/event-stream; charset=utf-8");
            headers.add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            CountDownLatch broken = new CountDownLatch(1);
            Object lock = new Object();
            EventBus.Subscription subscription = coordinator.events().subscribe(event -> {
                String frame = "id: " + event.sequence() + "\nevent: " + event.type() + "\ndata: " + Json.toJson(event)
                        + "\n\n";
                write(out, lock, frame, broken);
            }, after);
            try {
                while (!broken.await(KEEP_ALIVE_MILLIS, TimeUnit.MILLISECONDS)) {
                    write(out, lock, ": keep-alive\n\n", broken);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                subscription.close();
            }
        } finally {
            streams.decrementAndGet();
        }
    }

    private static void write(OutputStream out, Object lock, String text, CountDownLatch broken) {
        synchronized (lock) {
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                broken.countDown();
            }
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (settings.token() == null) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Bearer " + settings.token();
        return header != null && MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private void cors(Headers headers) {
        if (settings.corsOrigin() != null) {
            headers.add("Access-Control-Allow-Origin", settings.corsOrigin());
            headers.add("Access-Control-Allow-Headers", "Authorization, Content-Type, Last-Event-ID");
            headers.add("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS");
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                throw new IllegalArgumentException("Request bodies are limited to " + MAX_BODY_BYTES + " bytes");
            }
            return body;
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    values.put(pair.substring(0, eq), java.net.URLDecoder.decode(pair.substring(eq + 1),
                            StandardCharsets.UTF_8));
                }
            }
        }
        return values;
    }

    private interface Action {
        void run() throws IOException;
    }

    private static void requireMethod(HttpExchange exchange, String method, String expected, Action action)
            throws IOException {
        if (expected.equals(method)) {
            action.run();
        } else {
            methodNotAllowed(exchange);
        }
    }

    private static void methodNotAllowed(HttpExchange exchange) throws IOException {
        error(exchange, 405, "METHOD_NOT_ALLOWED", exchange.getRequestMethod() + " is not allowed on "
                + exchange.getRequestURI().getPath());
    }

    private static void error(HttpExchange exchange, int status, String code, String message) throws IOException {
        json(exchange, status, Map.of("error", Map.of("code", code, "message", message)));
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.toBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
