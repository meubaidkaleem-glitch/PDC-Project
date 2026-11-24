package org.districompute.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.districompute.app.testing.ApiClient;
import org.districompute.app.testing.ApiClient.Response;
import org.districompute.app.testing.TestDatabase;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * The application end to end over real HTTP: a MySQL container, the embedded coordinator, two worker nodes on
 * loopback TCP, the browser's session and CSRF handling. No component is mocked.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationIntegrationTest {

    private static final String SECRET = "integration-test-cluster-secret";
    private static final String ADMIN_PASSWORD = "admin-password-for-tests";
    private static final String USER_PASSWORD = "alice-password-for-tests";
    private static final Path DATA;

    static {
        try {
            DATA = Files.createTempDirectory("districompute-it");
            Files.writeString(DATA.resolve("cluster.secret"), SECRET);
            Files.writeString(DATA.resolve("admin.password"), ADMIN_PASSWORD);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("districompute.data-dir", DATA::toString);
        registry.add("districompute.coordinator.secret-file", () -> DATA.resolve("cluster.secret").toString());
        registry.add("districompute.security.admin-password-file", () -> DATA.resolve("admin.password").toString());
    }

    @LocalServerPort
    private int port;
    @Autowired
    private Coordinator coordinator;

    private final List<WorkerNode> workers = new ArrayList<>();
    private ApiClient admin;
    private ApiClient alice;

    @BeforeAll
    void start() {
        for (String id : List.of("w1", "w2")) {
            WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(),
                    SharedSecret.of(SECRET)).workerId(id).maxConcurrentTasks(2).build());
            assertTrue(node.awaitRegistered(Duration.ofSeconds(20)), node.lastError());
            workers.add(node);
        }
        admin = new ApiClient(port);
        assertEquals(200, admin.login("admin", ADMIN_PASSWORD).status());
        Response created = admin.post("/api/v1/users", Map.of("username", "alice", "password", USER_PASSWORD,
                "role", "USER"));
        assertEquals(201, created.status(), created.body());
        alice = new ApiClient(port);
        assertEquals(200, alice.login("alice", USER_PASSWORD).status());
    }

    @AfterAll
    void stop() {
        workers.forEach(WorkerNode::abort);
    }

    @Test
    void anonymousCallsAreRejectedAndWritesNeedTheCsrfToken() {
        ApiClient anonymous = new ApiClient(port);
        Response unauthenticated = anonymous.get("/api/v1/experiments");
        assertEquals(401, unauthenticated.status());
        assertEquals("UNAUTHENTICATED", unauthenticated.json().path("code").stringValue(), "a problem, not a redirect");
        assertEquals(200, anonymous.get("/api/v1/system/info").status(), "versions are public");
        assertFalse(anonymous.get("/api/v1/auth/session").json().path("authenticated").asBoolean());
        assertEquals(401, new ApiClient(port).login("alice", "wrong-password").status());
        Response withoutToken = alice.postWithoutCsrf("/api/v1/tasks/validate", draft("primes.count",
                Map.of("upperBound", 1000), "SEQUENTIAL_CPU"));
        assertEquals(403, withoutToken.status());
        assertEquals("CSRF_TOKEN_INVALID", withoutToken.json().path("code").stringValue(), withoutToken.body());
        assertEquals("alice", alice.get("/api/v1/auth/session").json().path("username").stringValue());
        JsonNode info = anonymous.get("/api/v1/system/info").json();
        assertEquals("0.1", info.path("protocol").stringValue());
    }

    @Test
    void theOpenApiDocumentDescribesTheApi() {
        assertEquals(401, new ApiClient(port).get("/v3/api-docs").status(), "documentation needs a session");
        Response docs = alice.get("/v3/api-docs");
        assertEquals(200, docs.status(), docs.body());
        JsonNode paths = docs.json().path("paths");
        for (String path : List.of("/api/v1/experiments", "/api/v1/experiments/{id}", "/api/v1/tasks/analyze",
                "/api/v1/algorithms", "/api/v1/workers", "/api/v1/workers/{id}/deactivate", "/api/v1/inputs")) {
            assertTrue(paths.has(path), "missing " + path);
        }
    }

    @Test
    void theCatalogIsCoresRegistry() {
        JsonNode algorithms = alice.get("/api/v1/algorithms").json();
        assertEquals(10, algorithms.size());
        JsonNode multiply = alice.get("/api/v1/algorithms/matrix.multiply").json();
        assertEquals("MATRIX", multiply.path("category").stringValue());
        Map<String, Boolean> executable = new LinkedHashMap<>();
        multiply.path("modes").forEach(m -> executable.put(m.path("mode").stringValue(),
                m.path("executable").asBoolean()));
        assertEquals(Map.of("SEQUENTIAL_CPU", true, "MULTITHREADED_CPU", true, "DISTRIBUTED_CPU", true, "GPU", false,
                "HYBRID", false), executable, "GPU and hybrid are never offered as available");
        Map<String, Boolean> categories = new LinkedHashMap<>();
        alice.get("/api/v1/categories").json().forEach(c -> categories.put(c.path("id").stringValue(),
                c.path("available").asBoolean()));
        assertTrue(categories.get("MATRIX") && categories.get("NUMERICAL") && categories.get("DATA"));
        assertFalse(categories.get("IMAGE_SIGNAL") || categories.get("SIMULATION"),
                "categories without Core algorithms are not presented as functional");
        assertEquals(404, alice.get("/api/v1/algorithms/no.such").status());
    }

    @Test
    void invalidTasksAreRejectedWithCoresViolations() {
        Response invalid = alice.post("/api/v1/tasks/validate", draft("matrix.multiply",
                Map.of("rowsA", -3, "colsA", 4, "colsB", 4), "SEQUENTIAL_CPU"));
        assertEquals(400, invalid.status(), invalid.body());
        assertEquals("VALIDATION_FAILED", invalid.json().path("code").stringValue());
        // The path is the one the form uses to place the message next to its field.
        assertEquals("parameters.rowsA", invalid.json().path("violations").path(0).path("field").stringValue(),
                invalid.body());

        Response gpu = alice.post("/api/v1/tasks/validate", draft("primes.count", Map.of("upperBound", 1000), "GPU"));
        assertEquals(400, gpu.status());
        assertTrue(gpu.body().contains("execution.mode") && gpu.body().contains("GPU execution is not available"),
                gpu.body());

        Response valid = alice.post("/api/v1/tasks/validate", draft("matrix.multiply",
                Map.of("rowsA", 64, "colsA", 48, "colsB", 40), "MULTITHREADED_CPU"));
        assertEquals(200, valid.status(), valid.body());
        assertTrue(valid.json().path("workload").path("estimatedOperations").asDouble() > 0);
    }

    @Test
    void theAdvisorExplainsEveryModeWithTheClustersResources() {
        Response response = alice.post("/api/v1/tasks/analyze", draft("primes.count",
                Map.of("upperBound", 20_000_000), "DISTRIBUTED_CPU"));
        assertEquals(200, response.status(), response.body());
        JsonNode advice = response.json();
        assertEquals(5, advice.path("modes").size());
        Set<String> eligible = new HashSet<>();
        advice.path("resources").path("eligibleWorkers").forEach(w -> eligible.add(w.stringValue()));
        assertEquals(Set.of("w1", "w2"), eligible);
        advice.path("modes").forEach(m -> {
            if (Set.of("GPU", "HYBRID").contains(m.path("mode").stringValue())) {
                assertFalse(m.path("executable").asBoolean());
            }
            assertTrue(Set.of("MEASURED", "HISTORICAL", "THEORETICAL", "NONE")
                    .contains(m.path("estimateBasis").stringValue()));
        });
        assertTrue(Set.of("HIGH", "MEDIUM", "LOW", "NONE").contains(advice.path("confidence").stringValue()));
        assertFalse(advice.path("explanation").stringValue().isBlank());
    }

    @Test
    void aMatrixMultiplicationRunsInEveryModeWithTheSameVerifiedResult() {
        Map<String, Object> parameters = Map.of("rowsA", 96, "colsA", 64, "colsB", 80, "seed", 7);
        String sequential = create("seq", draft("matrix.multiply", parameters, "SEQUENTIAL_CPU",
                Map.of("repetitions", 2)));
        String multithreaded = create("mt", draft("matrix.multiply", parameters, "MULTITHREADED_CPU",
                Map.of("threads", 2)));
        String distributed = create("dist", draft("matrix.multiply", parameters, "DISTRIBUTED_CPU",
                Map.of("schedulingPolicy", "DYNAMIC", "partitionsPerSlot", 2)));
        Set<String> digests = new HashSet<>();
        for (String id : List.of(sequential, multithreaded, distributed)) {
            JsonNode detail = await(alice, id);
            assertEquals("COMPLETED", detail.path("summary").path("status").stringValue(), detail.toString());
            for (JsonNode run : detail.path("runs")) {
                assertEquals("COMPLETED", run.path("status").stringValue());
                assertTrue(run.path("verificationPassed").asBoolean());
                digests.add(run.path("resultDigest").stringValue());
            }
            for (JsonNode result : detail.path("results")) {
                assertEquals("VERIFIED", result.path("validationStatus").stringValue());
                assertTrue(result.path("stored").asBoolean(), result.toString());
                assertTrue(result.path("storedDigestMatch").asBoolean(), "the stored value is the verified one");
            }
            assertTrue(metricNames(detail).contains("wall_time"));
        }
        assertEquals(1, digests.size(), "sequential, multithreaded and distributed results are bitwise equal");

        JsonNode seq = await(alice, sequential);
        assertEquals(2, seq.path("runs").size());
        JsonNode wall = stat(seq, "wall_time");
        assertEquals(2, wall.path("count").asInt(), "statistics over both repetitions");

        JsonNode dist = await(alice, distributed);
        Set<String> names = metricNames(dist);
        assertTrue(names.containsAll(Set.of("kernel_time_sum", "transfer_time_sum", "bytes_sent", "workers_used",
                "communication_ratio")), names.toString());
        JsonNode live = alice.get("/api/v1/experiments/" + distributed + "/live").json();
        assertTrue(live.path("totalPartitions").asInt() >= 1);
        assertEquals(live.path("totalPartitions").asInt(), live.path("completedPartitions").asInt());
        assertFalse(live.path("partitions").isEmpty());

        Response preview = alice.get("/api/v1/experiments/" + distributed + "/runs/1/result/preview?rows=5&columns=4");
        assertEquals(200, preview.status(), preview.body());
        assertEquals(96, preview.json().path("totalRows").asInt());
        assertEquals(5, preview.json().path("rows").size());
        assertEquals(4, preview.json().path("rows").get(0).size());
        Response download = alice.get("/api/v1/experiments/" + distributed + "/runs/1/result/download");
        assertEquals(200, download.status());
        assertTrue(download.disposition().contains("attachment"));
        assertEquals(97, download.body().split("\n").length, "header plus 96 rows");

        List<String> types = new ArrayList<>();
        alice.get("/api/v1/experiments/" + distributed + "/events?limit=1000").json()
                .forEach(e -> types.add(e.path("type").stringValue()));
        assertTrue(types.containsAll(List.of("RUN_SUBMITTED", "ATTEMPT_ASSIGNED", "TASK_FINISHED", "RUN_FINISHED")),
                types.toString());
        JsonNode task = alice.get("/api/v1/tasks/" + distributed + "-r1").json();
        assertEquals("COMPLETED", task.path("run").path("status").stringValue());
    }

    @Test
    void experimentsOfOneWorkloadAreComparedAgainstTheSequentialBaseline() {
        Map<String, Object> parameters = Map.of("rowsA", 80, "colsA", 72, "colsB", 64, "seed", 11);
        String parallel = create("cmp-mt", draft("matrix.multiply", parameters, "MULTITHREADED_CPU",
                Map.of("threads", 2, "repetitions", 2)));
        String sequential = create("cmp-seq", draft("matrix.multiply", parameters, "SEQUENTIAL_CPU",
                Map.of("repetitions", 3)));
        String distributed = create("cmp-dist", draft("matrix.multiply", parameters, "DISTRIBUTED_CPU"));
        Map<String, Double> walls = new HashMap<>();
        Map<String, Double> executions = new HashMap<>();
        for (String id : List.of(parallel, sequential, distributed)) {
            JsonNode detail = await(alice, id);
            assertEquals("COMPLETED", detail.path("summary").path("status").stringValue(), detail.toString());
            walls.put(id, detail.path("summary").path("medianWallTimeMs").asDouble());
            executions.put(id, stat(detail, "execution_time").path("median").asDouble());
        }

        Response response = alice.post("/api/v1/analysis/compare",
                Map.of("experimentIds", List.of(parallel, sequential, distributed)));
        assertEquals(200, response.status(), response.body());
        JsonNode comparison = response.json();
        assertTrue(comparison.path("comparable").asBoolean(), comparison.toString());
        assertEquals(sequential, comparison.path("baselineId").stringValue(), "a sequential experiment is the baseline");
        assertEquals("EXECUTION_TIME", comparison.path("basis").stringValue(), "Core's time is the default basis");
        JsonNode mt = comparison.path("experiments").get(0);
        assertEquals(parallel, mt.path("id").stringValue(), "experiments keep the requested order");
        assertEquals(2, mt.path("completedRuns").asInt());
        assertEquals(executions.get(sequential) / executions.get(parallel), mt.path("speedup").asDouble(), 1e-9);
        assertEquals(mt.path("speedup").asDouble() / 2, mt.path("efficiency").asDouble(), 1e-9);
        assertEquals("thread", mt.path("efficiencyBasis").stringValue());
        JsonNode seq = comparison.path("experiments").get(1);
        assertEquals(1.0, seq.path("speedup").asDouble(), 1e-12);
        assertEquals(3, seq.path("wallTime").path("count").asInt());
        assertTrue(seq.path("breakdown").has("waiting") && seq.path("breakdown").has("compute_time")
                && seq.path("breakdown").has("other"), seq.path("breakdown").toString());
        JsonNode byWall = alice.post("/api/v1/analysis/compare", Map.of("experimentIds",
                List.of(parallel, sequential, distributed), "basis", "WALL_TIME")).json();
        assertEquals(walls.get(sequential) / walls.get(parallel),
                byWall.path("experiments").get(0).path("speedup").asDouble(), 1e-9);
        assertEquals(400, alice.post("/api/v1/analysis/compare", Map.of("experimentIds",
                List.of(parallel, sequential), "basis", "CPU_TIME")).status());
        JsonNode dist = comparison.path("experiments").get(2);
        assertEquals("worker", dist.path("efficiencyBasis").stringValue());
        assertTrue(dist.path("indicators").has("communication_ratio"), dist.path("indicators").toString());
        Set<String> codes = new HashSet<>();
        comparison.path("issues").forEach(i -> codes.add(i.path("code").stringValue()));
        assertTrue(codes.containsAll(Set.of("RESULTS_IDENTICAL", "SINGLE_RUN")), codes.toString());
        assertFalse(comparison.path("summary").isEmpty());

        String smaller = create("cmp-small", draft("matrix.multiply", Map.of("rowsA", 16, "colsA", 16, "colsB", 16),
                "SEQUENTIAL_CPU"));
        await(alice, smaller);
        JsonNode mismatch = alice.post("/api/v1/analysis/compare",
                Map.of("experimentIds", List.of(sequential, smaller))).json();
        assertFalse(mismatch.path("comparable").asBoolean(), "different sizes are not compared");
        assertTrue(mismatch.path("issues").toString().contains("DIMENSIONS_DIFFER"), mismatch.toString());
        assertTrue(mismatch.path("experiments").get(1).path("speedup").isNull());

        JsonNode group = null;
        for (JsonNode g : alice.get("/api/v1/analysis/workloads").json()) {
            if (g.path("experimentIds").toString().contains(sequential)) {
                group = g;
            }
        }
        assertTrue(group != null, "the workload is listed");
        assertEquals(3, group.path("modes").size(), group.toString());
        assertFalse(group.path("experimentIds").toString().contains(smaller), "another size is another workload");

        assertEquals(400, alice.post("/api/v1/analysis/compare", Map.of("experimentIds", List.of(sequential)))
                .status());
        assertEquals("DUPLICATE_EXPERIMENT", alice.post("/api/v1/analysis/compare",
                Map.of("experimentIds", List.of(sequential, sequential))).json().path("code").stringValue());
        assertEquals("BASELINE_NOT_COMPARED", alice.post("/api/v1/analysis/compare", Map.of("experimentIds",
                List.of(sequential, parallel), "baselineId", distributed)).json().path("code").stringValue());
        assertEquals(404, anotherUser().post("/api/v1/analysis/compare",
                Map.of("experimentIds", List.of(sequential, parallel))).status(), "other users' experiments stay hidden");
    }

    @Test
    void uploadedMatricesAreUsedAndTheResultIsExact() {
        String a = alice.upload("/api/v1/inputs?algorithm=matrix.add&parameter=matrixA", "a.csv", "text/csv",
                "c0,c1,c2\n1,2,3\n4,5,6\n").json().path("id").stringValue();
        JsonNode b = alice.upload("/api/v1/inputs?algorithm=matrix.add&parameter=matrixB", "b.csv", "text/csv",
                "10 20 30\n40 50 60\n").json();
        assertEquals(2, b.path("rows").asInt());
        assertEquals(3, b.path("columns").asInt());
        Map<String, Object> task = new LinkedHashMap<>(draft("matrix.add", Map.of("rows", 2, "cols", 3),
                "SEQUENTIAL_CPU"));
        task.put("inputs", Map.of("matrixA", a, "matrixB", b.path("id").stringValue()));
        String id = create("upload", task);
        JsonNode detail = await(alice, id);
        assertEquals("COMPLETED", detail.path("summary").path("status").stringValue(), detail.toString());
        assertEquals("UPLOAD", detail.path("inputSource").stringValue());
        String csv = alice.get("/api/v1/experiments/" + id + "/runs/1/result/download").body();
        assertEquals("c0,c1,c2\n11.0,22.0,33.0\n44.0,55.0,66.0\n", csv);

        Response ragged = alice.upload("/api/v1/inputs?algorithm=matrix.add&parameter=matrixA", "r.csv", "text/csv",
                "1,2\n3\n");
        assertEquals(400, ragged.status());
        assertEquals("RAGGED_ROWS", ragged.json().path("code").stringValue());
        assertEquals(400, alice.upload("/api/v1/inputs?algorithm=matrix.add&parameter=matrixA", "x.exe",
                "application/x-msdownload", "MZ").status());
        assertEquals(200, admin.get("/api/v1/inputs/" + a).status(), "administrators may read uploads");
        assertEquals(404, anotherUser().get("/api/v1/inputs/" + a).status(), "other users may not");
    }

    @Test
    void polynomialsAreParsedNeverEvaluated() {
        JsonNode parsed = alice.post("/api/v1/expressions/polynomial", Map.of("expression", "3x^2 - 2x + 1")).json();
        assertEquals("[1.0,-2.0,3.0]", parsed.path("coefficients").toString());
        assertEquals("3x^2 - 2x + 1", parsed.path("normalized").stringValue());
        Response rejected = alice.post("/api/v1/expressions/polynomial", Map.of("expression",
                "Runtime.getRuntime().exec('ls')"));
        assertEquals(400, rejected.status());
    }

    @Test
    void usersSeeOnlyTheirOwnExperiments() {
        String id = create("private", draft("primes.count", Map.of("upperBound", 100_000), "SEQUENTIAL_CPU"));
        await(alice, id);
        ApiClient bob = anotherUser();
        assertEquals(404, bob.get("/api/v1/experiments/" + id).status());
        assertEquals(404, bob.post("/api/v1/experiments/" + id + "/cancel", null).status());
        assertEquals(0, bob.get("/api/v1/experiments").json().path("totalItems").asInt());
        assertEquals(200, admin.get("/api/v1/experiments/" + id).status(), "administrators see every experiment");
        JsonNode page = alice.get("/api/v1/experiments?search=private&size=5").json();
        assertTrue(page.path("totalItems").asInt() >= 1);
    }

    @Test
    void workersAreListedAndAdministered() {
        JsonNode list = alice.get("/api/v1/workers").json();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        list.forEach(w -> byId.put(w.path("workerId").stringValue(), w));
        assertTrue(byId.keySet().containsAll(Set.of("w1", "w2")), byId.keySet().toString());
        JsonNode w1 = byId.get("w1");
        assertTrue(w1.path("connected").asBoolean());
        assertTrue(w1.path("cpuCores").asInt() > 0);
        assertEquals(2, w1.path("slots").asInt());
        assertFalse(w1.path("algorithms").isEmpty());
        assertFalse(w1.path("gpuAvailable").asBoolean(), "no GPU is reported, so none is shown");

        assertEquals(403, alice.post("/api/v1/workers/w2/deactivate", Map.of("reason", "test")).status());
        JsonNode deactivated = admin.post("/api/v1/workers/w2/deactivate", Map.of("reason", "maintenance")).json();
        assertEquals("DEACTIVATED", deactivated.path("status").stringValue());
        String id = create("only-w1", draft("primes.count", Map.of("upperBound", 2_000_000), "DISTRIBUTED_CPU",
                Map.of("targetPartitions", 6)));
        await(alice, id);
        Set<String> used = new HashSet<>();
        alice.get("/api/v1/experiments/" + id + "/live").json().path("partitions")
                .forEach(p -> p.path("attempts").forEach(at -> used.add(at.path("workerId").stringValue())));
        assertEquals(Set.of("w1"), used, "a deactivated worker receives no work");
        assertEquals("ACTIVE", admin.post("/api/v1/workers/w2/activate", null).json().path("status").stringValue());

        JsonNode test = alice.post("/api/v1/workers/w1/test", null).json();
        assertEquals("OK", test.path("verdict").stringValue(), test.toString());
        assertTrue(test.path("coordinatorListening").asBoolean());

        Response registered = admin.post("/api/v1/workers/register", Map.of("workerId", "lab-pc-99", "notes",
                "room 2"));
        assertEquals(201, registered.status(), registered.body());
        assertTrue(registered.json().path("commandLine").stringValue().contains("--id lab-pc-99"));
        assertFalse(registered.body().contains(SECRET), "the secret is never returned");
        assertEquals("NEVER_CONNECTED", alice.get("/api/v1/workers/lab-pc-99").json().path("status").stringValue());
        assertEquals("NEVER_CONNECTED", alice.post("/api/v1/workers/lab-pc-99/test", null).json().path("verdict")
                .stringValue());
        assertEquals(204, admin.delete("/api/v1/workers/lab-pc-99").status());
        assertEquals(404, alice.get("/api/v1/workers/lab-pc-99").status());
    }

    @Test
    void aRunningExperimentCanBeCancelled() {
        String id = create("cancel", draft("primes.count", Map.of("upperBound", 40_000_000_000L),
                "DISTRIBUTED_CPU", Map.of("targetPartitions", 400)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!"RUNNING".equals(alice.get("/api/v1/experiments/" + id).json().path("summary").path("status")
                .stringValue()) && System.nanoTime() < deadline) {
            sleep(50);
        }
        assertEquals(202, alice.post("/api/v1/experiments/" + id + "/cancel", null).status());
        JsonNode detail = await(alice, id);
        assertEquals("CANCELLED", detail.path("summary").path("status").stringValue(), detail.toString());
        assertEquals(409, alice.post("/api/v1/experiments/" + id + "/cancel", null).status());
    }

    @Test
    void liveEventsArriveOverServerSentEvents() throws Exception {
        try (InputStream stream = alice.stream("/api/v1/events/stream")) {
            CompletableFuture<List<String>> received = CompletableFuture.supplyAsync(() -> read(stream,
                    "event:EXPERIMENT_FINISHED"));
            String id = create("sse", draft("primes.count", Map.of("upperBound", 1_000_000), "MULTITHREADED_CPU",
                    Map.of("threads", 2)));
            List<String> lines = received.get(30, TimeUnit.SECONDS);
            assertTrue(lines.stream().anyMatch(l -> l.equals("event:RUN_SUBMITTED")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("data:") && l.contains(id)), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("id:")), "stored events carry ids for resumption");
        }
    }

    private ApiClient anotherUser() {
        ApiClient bob = new ApiClient(port);
        Response created = admin.post("/api/v1/users", Map.of("username", "bob", "password", "bob-password-for-tests",
                "role", "USER"));
        assertTrue(created.status() == 201 || created.status() == 409, created.body());
        assertEquals(200, bob.login("bob", "bob-password-for-tests").status());
        return bob;
    }

    private String create(String name, Map<String, Object> task) {
        Response response = alice.post("/api/v1/experiments", Map.of("name", name, "task", task));
        assertEquals(202, response.status(), response.body());
        return response.json().path("summary").path("id").stringValue();
    }

    private static JsonNode await(ApiClient client, String id) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (true) {
            JsonNode detail = client.get("/api/v1/experiments/" + id).json();
            String status = detail.path("summary").path("status").stringValue();
            if (Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status)) {
                return detail;
            }
            assertTrue(System.nanoTime() < deadline, "experiment " + id + " did not finish: " + detail);
            sleep(100);
        }
    }

    private static Set<String> metricNames(JsonNode detail) {
        Set<String> names = new HashSet<>();
        detail.path("metrics").forEach(m -> names.add(m.path("name").stringValue()));
        return names;
    }

    private static JsonNode stat(JsonNode detail, String name) {
        for (JsonNode m : detail.path("metrics")) {
            if (name.equals(m.path("name").stringValue())) {
                return m;
            }
        }
        throw new AssertionError("no metric " + name);
    }

    static Map<String, Object> draft(String algorithm, Map<String, Object> parameters, String mode) {
        return draft(algorithm, parameters, mode, Map.of());
    }

    static Map<String, Object> draft(String algorithm, Map<String, Object> parameters, String mode,
            Map<String, Object> settings) {
        Map<String, Object> execution = new LinkedHashMap<>(settings);
        execution.put("mode", mode);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("algorithm", algorithm);
        task.put("parameters", parameters);
        task.put("execution", execution);
        return task;
    }

    private static List<String> read(InputStream stream, String until) {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (line.equals(until)) {
                    return lines;
                }
            }
        } catch (IOException e) {
            lines.add("closed: " + e.getMessage());
        }
        return lines;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
