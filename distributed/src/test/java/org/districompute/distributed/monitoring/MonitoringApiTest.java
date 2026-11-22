package org.districompute.distributed.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.Json;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The coordinator's HTTP API over real HTTP, with two real workers. */
@Tag("integration")
class MonitoringApiTest {

    private static final SharedSecret SECRET = SharedSecret.of("monitoring-api-secret!");
    private static final String TOKEN = "test-token-0123456789";

    private static Coordinator coordinator;
    private static MonitoringServer api;
    private static final List<WorkerNode> WORKERS = new ArrayList<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000))
                .tickInterval(Duration.ofMillis(20)).build());
        api = MonitoringServer.start(coordinator, new MonitoringServer.Settings("127.0.0.1", 0, TOKEN, null, 4));
        base = "http://127.0.0.1:" + api.port() + "/api/v1";
        for (String id : List.of("api-w1", "api-w2")) {
            WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                    .workerId(id).maxConcurrentTasks(2).build());
            WORKERS.add(node);
            assertTrue(node.awaitRegistered(Duration.ofSeconds(10)));
        }
    }

    @AfterAll
    static void stop() {
        WORKERS.forEach(WorkerNode::abort);
        api.close();
        coordinator.close();
    }

    @Test
    void requestsWithoutTheTokenAreRefused() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertEquals("UNAUTHORIZED", Json.readTree(bytes(response)).path("error").path("code").stringValue());
        HttpResponse<String> wrong = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                .header("Authorization", "Bearer not-the-token-at-all").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, wrong.statusCode());
    }

    @Test
    void healthWorkersCatalogAndMetrics() throws Exception {
        JsonNode health = get("/health", 200);
        assertEquals("UP", health.path("status").stringValue());
        assertEquals("0.1", health.path("protocolVersion").stringValue());
        assertEquals(2, health.path("workersConnected").asInt());

        JsonNode workers = get("/workers", 200);
        assertEquals(2, workers.size());
        JsonNode one = get("/workers/api-w1", 200);
        assertEquals("ACTIVE", one.path("status").stringValue());
        assertEquals(2, one.path("capabilities").path("maxConcurrentTasks").asInt());
        assertEquals(10, one.path("capabilities").path("algorithms").size());
        assertEquals("NOT_FOUND", get("/workers/nobody", 404).path("error").path("code").stringValue());

        JsonNode catalog = get("/catalog", 200);
        JsonNode primes = null;
        for (JsonNode entry : catalog) {
            if ("primes.count".equals(entry.path("descriptor").path("id").stringValue())) {
                primes = entry;
            }
        }
        assertEquals(2, primes.path("workers").size(), "both workers offer primes.count");
        assertTrue(get("/metrics", 200).has("tasksSubmitted"));
        assertEquals(405, send("DELETE", "/workers", null).statusCode());
        assertEquals(404, send("GET", "/nothing", null).statusCode());
    }

    @Test
    void aTaskSubmittedOverHttpRunsAndIsVisibleInEveryView() throws Exception {
        String body = "{\"task\":{\"schemaVersion\":\"0.1\",\"taskId\":\"http-primes\",\"experimentId\":\"http\","
                + "\"algorithm\":{\"id\":\"primes.count\"},\"parameters\":{\"lowerBound\":0,\"upperBound\":3000000},"
                + "\"constraints\":{\"mode\":\"DISTRIBUTED_CPU\",\"partitioning\":{\"targetPartitions\":6}},"
                + "\"createdAt\":\"2026-11-22T10:00:00Z\"},\"policy\":\"DYNAMIC\",\"maxAttempts\":2}";
        HttpResponse<String> accepted = send("POST", "/tasks", body);
        assertEquals(202, accepted.statusCode(), accepted.body());
        assertEquals("/api/v1/tasks/http-primes", accepted.headers().firstValue("Location").orElseThrow());
        Await.until(Duration.ofSeconds(30), () -> "COMPLETED".equals(getQuietly("/tasks/http-primes").path("status")
                .stringValue()), "the task to complete");
        JsonNode task = get("/tasks/http-primes", 200);
        assertEquals("DYNAMIC", task.path("policy").stringValue());
        assertEquals(6, task.path("completedPartitions").asInt());
        assertTrue(task.path("result").path("verification").path("passed").asBoolean());
        assertTrue(task.path("metrics").path("kernelNanos").asLong() > 0);
        JsonNode attempt = task.path("partitions").get(0).path("attempts").get(0);
        assertTrue(attempt.path("assignment").path("explanation").stringValue().startsWith("dynamic"));

        boolean listed = false;
        for (JsonNode summary : get("/tasks", 200)) {
            listed |= "http-primes".equals(summary.path("taskId").stringValue());
            assertFalse(summary.has("partitions"), "the list holds summaries only");
        }
        assertTrue(listed);
        assertEquals(409, send("POST", "/tasks", body).statusCode(), "task ids are unique");
        assertEquals(400, send("POST", "/tasks", "{not json").statusCode());
        HttpResponse<String> invalid = send("POST", "/tasks", "{\"task\":{\"schemaVersion\":\"0.1\","
                + "\"taskId\":\"bad\",\"algorithm\":{\"id\":\"primes.count\"},\"parameters\":{},"
                + "\"constraints\":{\"mode\":\"WARP_DRIVE\"},\"createdAt\":\"2026-11-22T10:00:00Z\"}}");
        assertEquals(400, invalid.statusCode(), invalid.body());
        HttpResponse<String> unknown = send("POST", "/tasks", "{\"task\":{\"schemaVersion\":\"0.1\","
                + "\"taskId\":\"unknown-alg\",\"algorithm\":{\"id\":\"no.such\"},\"parameters\":{},"
                + "\"constraints\":{\"mode\":\"DISTRIBUTED_CPU\"},\"createdAt\":\"2026-11-22T10:00:00Z\"}}");
        assertEquals("UNKNOWN_ALGORITHM", Json.readTree(bytes(unknown)).path("error").path("code").stringValue());
        assertEquals(404, send("POST", "/tasks/nobody/cancel", "").statusCode());
    }

    @Test
    void theSchedulingPolicyCanBeReadAndChanged() throws Exception {
        assertEquals("LEAST_LOADED", get("/scheduling", 200).path("policy").stringValue());
        assertEquals(5, get("/scheduling", 200).path("available").size());
        HttpResponse<String> changed = send("PUT", "/scheduling", "{\"policy\":\"ROUND_ROBIN\"}");
        assertEquals(200, changed.statusCode());
        assertEquals("ROUND_ROBIN", Json.readTree(bytes(changed)).path("policy").stringValue());
        assertEquals(400, send("PUT", "/scheduling", "{\"policy\":\"RANDOM\"}").statusCode());
        send("PUT", "/scheduling", "{\"policy\":\"LEAST_LOADED\"}");
    }

    @Test
    void eventsCanBeReadAndStreamedWithServerSentEvents() throws Exception {
        long before = coordinator.events().lastSequence();
        HttpRequest streamRequest = HttpRequest.newBuilder(URI.create(base + "/events/stream?after=" + before))
                .header("Authorization", "Bearer " + TOKEN).build();
        HttpResponse<InputStream> stream = HTTP.send(streamRequest, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, stream.statusCode());
        assertTrue(stream.headers().firstValue("Content-Type").orElseThrow().startsWith("text/event-stream"));
        CompletableFuture<List<String>> lines = CompletableFuture.supplyAsync(() -> readUntilFinished(stream.body()));
        HttpResponse<String> accepted = send("POST", "/tasks", "{\"task\":{\"schemaVersion\":\"0.1\","
                + "\"taskId\":\"sse-task\",\"algorithm\":{\"id\":\"primes.count\"},"
                + "\"parameters\":{\"lowerBound\":0,\"upperBound\":1000000},\"constraints\":{\"mode\":"
                + "\"DISTRIBUTED_CPU\"},\"createdAt\":\"2026-11-22T10:00:00Z\"}}");
        assertEquals(202, accepted.statusCode());
        List<String> received = lines.get(30, TimeUnit.SECONDS);
        assertTrue(received.contains("event: TASK_SUBMITTED"), received.toString());
        assertTrue(received.contains("event: ATTEMPT_ASSIGNED"));
        String finished = received.get(received.indexOf("event: TASK_FINISHED") + 1);
        JsonNode event = Json.readTree(finished.substring("data: ".length()).getBytes(StandardCharsets.UTF_8));
        assertEquals("sse-task", event.path("taskId").stringValue());
        assertEquals("COMPLETED", event.path("details").path("status").stringValue());
        stream.body().close();

        JsonNode recent = get("/events?after=" + before + "&limit=5", 200);
        assertEquals(5, recent.size());
        assertTrue(recent.get(0).path("sequence").asLong() > before);
    }

    private static List<String> readUntilFinished(InputStream in) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean finished = false;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (finished && line.startsWith("data: ")) {
                    return lines;
                }
                finished |= line.equals("event: TASK_FINISHED");
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return lines;
    }

    private static JsonNode get(String path, int expectedStatus) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertEquals(expectedStatus, response.statusCode(), response.body());
        return Json.readTree(bytes(response));
    }

    private static JsonNode getQuietly(String path) {
        try {
            return Json.readTree(bytes(send("GET", path, null)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Authorization", "Bearer " + TOKEN).timeout(Duration.ofSeconds(10));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] bytes(HttpResponse<String> response) {
        return response.body().getBytes(StandardCharsets.UTF_8);
    }
}
