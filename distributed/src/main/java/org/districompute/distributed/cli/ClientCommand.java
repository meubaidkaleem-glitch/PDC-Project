package org.districompute.distributed.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import org.districompute.distributed.Json;
import tools.jackson.databind.JsonNode;

/**
 * Small clients of a coordinator's HTTP API, for operators and demonstrations: {@code submit}, {@code status} and
 * {@code advise}.
 */
public final class ClientCommand {

    /** Options accepted by the client commands. */
    public static final Set<String> OPTIONS = Set.of("api", "token-file", "policy", "wait", "max-workers",
            "override-reason", "timeout-s");
    /** Boolean options of the client commands. */
    public static final Set<String> FLAGS = Set.of("wait");

    private ClientCommand() {
    }

    /**
     * Runs a client command.
     *
     * @param command {@code submit}, {@code status} or {@code advise}
     * @param options parsed options (positional: the task file)
     * @param out standard output
     * @return exit status: 0 on success, 1 if the task did not complete, 2 for usage errors
     * @throws IOException if the API cannot be reached
     * @throws InterruptedException if interrupted while waiting
     */
    public static int run(String command, Options options, PrintStream out) throws IOException, InterruptedException {
        options.requireKnown(OPTIONS);
        String api = options.get("api", "http://127.0.0.1:8650").replaceAll("/+$", "") + "/api/v1";
        String token = null;
        if (options.has("token-file")) {
            token = Files.readString(Path.of(options.get("token-file", "")), StandardCharsets.UTF_8).strip();
        } else if (System.getenv(CoordinatorCommand.TOKEN_VARIABLE) != null) {
            token = System.getenv(CoordinatorCommand.TOKEN_VARIABLE).strip();
        }
        Http http = new Http(api, token);
        return switch (command) {
            case "submit" -> submit(http, options, out);
            case "advise" -> advise(http, options, out);
            case "status" -> status(http, out);
            default -> throw new IllegalArgumentException("Unknown client command " + command);
        };
    }

    private static int submit(Http http, Options options, PrintStream out) throws IOException, InterruptedException {
        JsonNode task = readTask(options);
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("task", task);
        if (options.has("policy")) {
            body.put("policy", options.get("policy", "").toUpperCase(Locale.ROOT).replace('-', '_'));
        }
        if (options.has("max-workers")) {
            body.put("maxWorkers", options.getInt("max-workers", 1));
        }
        if (options.has("override-reason")) {
            body.put("overrideReason", options.get("override-reason", ""));
        }
        JsonNode accepted = http.send("POST", "/tasks", Json.toJson(body), 202);
        String taskId = accepted.path("taskId").stringValue();
        out.println("Submitted task " + taskId);
        if (!options.getBoolean("wait", false)) {
            return 0;
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(options.getInt("timeout-s", 3600)).toNanos();
        JsonNode snapshot;
        String status;
        do {
            Thread.sleep(250);
            snapshot = http.send("GET", "/tasks/" + taskId, null, 200);
            status = snapshot.path("status").stringValue();
        } while (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status) && System.nanoTime() < deadline);
        printOutcome(snapshot, out);
        return "COMPLETED".equals(status) ? 0 : 1;
    }

    private static int advise(Http http, Options options, PrintStream out) throws IOException, InterruptedException {
        JsonNode advice = http.send("POST", "/advice", Json.toJson(readTask(options)), 200);
        out.println(advice.path("explanation").stringValue());
        for (JsonNode a : advice.path("assessment").path("assessments")) {
            out.printf(Locale.ROOT, "  %-18s %-12s %s%n", a.path("mode").stringValue(), a.path("verdict").stringValue(),
                    String.join("; ", a.path("reasons").values().stream().map(JsonNode::stringValue).toList()));
        }
        for (JsonNode note : advice.path("runtime").path("notes")) {
            out.println("  note: " + note.stringValue());
        }
        return 0;
    }

    private static int status(Http http, PrintStream out) throws IOException, InterruptedException {
        JsonNode health = http.send("GET", "/health", null, 200);
        out.println("Coordinator " + health.path("coordinatorId").stringValue() + " (runtime "
                + health.path("runtimeVersion").stringValue() + ", protocol " + health.path("protocolVersion")
                .stringValue() + ")");
        out.println("Workers:");
        for (JsonNode w : http.send("GET", "/workers", null, 200)) {
            out.printf(Locale.ROOT, "  %-20s %-12s %2d/%-2d slots  %2d cores  %s%n",
                    w.path("workerId").stringValue(), w.path("status").stringValue(), w.path("assignedTasks").asInt(),
                    w.path("maxConcurrentTasks").asInt(), w.path("capabilities").path("cpuCores").asInt(),
                    w.path("endpoint").stringValue(""));
        }
        out.println("Tasks:");
        for (JsonNode t : http.send("GET", "/tasks", null, 200)) {
            out.printf(Locale.ROOT, "  %-28s %-10s %3d/%-3d partitions  %s%n", t.path("taskId").stringValue(),
                    t.path("status").stringValue(), t.path("completedPartitions").asInt(),
                    t.path("totalPartitions").asInt(), t.path("algorithm").path("id").stringValue());
        }
        JsonNode m = http.send("GET", "/metrics", null, 200);
        out.printf(Locale.ROOT, "Attempts: %d dispatched, %d succeeded, %d failed, %d retries%n",
                m.path("attemptsDispatched").asLong(), m.path("attemptsSucceeded").asLong(),
                m.path("attemptsFailed").asLong(), m.path("partitionsRetried").asLong());
        return 0;
    }

    private static void printOutcome(JsonNode snapshot, PrintStream out) {
        JsonNode result = snapshot.path("result");
        out.println("Status: " + snapshot.path("status").stringValue());
        if (result.has("result")) {
            out.println("Digest: " + result.path("result").path("digest").stringValue());
            out.println("Verification: " + result.path("verification").path("method").stringValue() + " -> "
                    + (result.path("verification").path("passed").asBoolean() ? "passed" : "FAILED"));
        }
        if (result.has("error")) {
            out.println("Error: " + result.path("error").path("code").stringValue() + ": "
                    + result.path("error").path("message").stringValue());
        }
        JsonNode metrics = snapshot.path("metrics");
        if (!metrics.isMissingNode()) {
            out.printf(Locale.ROOT, "Partitions %d, attempts %d, retries %d, workers %d%n", metrics.path("partitions")
                    .asInt(), metrics.path("attempts").asInt(), metrics.path("retries").asInt(),
                    metrics.path("workersUsed").asInt());
            out.printf(Locale.ROOT, "Wall: total %.3f s, distributed %.3f s, aggregation %.3f s%n",
                    seconds(metrics, "totalNanos"), seconds(metrics, "distributedNanos"),
                    seconds(metrics, "aggregationNanos"));
            out.printf(Locale.ROOT, "Sums: kernel %.3f s, transfer %.3f s, scheduling %.3f ms, %.1f MiB moved%n",
                    seconds(metrics, "kernelNanos"), seconds(metrics, "transferNanos"),
                    metrics.path("schedulingNanos").asLong() / 1e6,
                    (metrics.path("bytesSent").asLong() + metrics.path("bytesReceived").asLong()) / 1048576.0);
        }
    }

    private static double seconds(JsonNode metrics, String field) {
        return metrics.path(field).asLong() / 1e9;
    }

    private static JsonNode readTask(Options options) throws IOException {
        if (options.positional().isEmpty()) {
            throw new IllegalArgumentException("A task file (Core TaskSpec JSON) is required");
        }
        return Json.readTree(Files.readAllBytes(Path.of(options.positional().get(0))));
    }

    /** Minimal JSON-over-HTTP client. */
    private record Http(String base, String token) {

        private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .build();

        JsonNode send(String method, String path, String body, int expected)
                throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (token != null) {
                request.header("Authorization", "Bearer " + token);
            }
            HttpResponse<byte[]> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != expected) {
                JsonNode error = Json.readTree(response.body()).path("error");
                throw new IOException("HTTP " + response.statusCode() + " from " + path + ": "
                        + error.path("code").stringValue("") + " " + error.path("message").stringValue(""));
            }
            return Json.readTree(response.body());
        }
    }
}
