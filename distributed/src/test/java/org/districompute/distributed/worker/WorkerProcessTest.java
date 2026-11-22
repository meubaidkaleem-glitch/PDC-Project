package org.districompute.distributed.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.DistributedCli;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Workers started as separate JVM processes, as on real machines. */
@Tag("process")
class WorkerProcessTest {

    private static final String SECRET = "process-test-shared-secret";
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void independentWorkerProcessesRegisterAndTheirExitIsDetected(@TempDir Path dir) throws Exception {
        Path secretFile = dir.resolve("cluster.secret");
        Files.writeString(secretFile, SECRET + "\n", StandardCharsets.UTF_8);
        CoordinatorConfig config = CoordinatorConfig.builder(SharedSecret.of(SECRET)).bindAddress("127.0.0.1")
                .port(0).heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(1500))
                .tickInterval(Duration.ofMillis(20)).build();
        List<Process> processes = new ArrayList<>();
        try (Coordinator coordinator = Coordinator.start(config)) {
            for (String id : List.of("proc-graceful", "proc-killed")) {
                processes.add(launch(id, coordinator.port(), secretFile, dir.resolve(id + ".log")));
            }
            // The first heartbeat proves that the worker process itself completed its registration (the
            // coordinator lists a worker as soon as it sends REGISTER_ACK).
            Await.until(WAIT, () -> coordinator.workers().stream()
                    .filter(w -> w.status() == WorkerStatus.ACTIVE && w.load().freeMemoryBytes() > 0).count() == 2,
                    "both worker processes to register and send heartbeats");
            assertTrue(coordinator.worker("proc-graceful").orElseThrow().endpoint().startsWith("127.0.0.1:"));

            // SIGTERM: the worker's shutdown hook announces SHUTDOWN and disconnects cleanly.
            processes.get(0).destroy();
            assertTrue(processes.get(0).waitFor(30, TimeUnit.SECONDS));
            Await.until(WAIT, () -> coordinator.worker("proc-graceful").orElseThrow().status()
                    == WorkerStatus.DISCONNECTED, "the graceful exit");

            // SIGKILL: no goodbye; the coordinator notices the broken connection.
            processes.get(1).destroyForcibly();
            assertTrue(processes.get(1).waitFor(30, TimeUnit.SECONDS));
            Await.until(WAIT, () -> coordinator.worker("proc-killed").orElseThrow().status()
                    == WorkerStatus.DISCONNECTED, "the crash to be detected");
            assertEquals(2, coordinator.workers().size());
            String log = Files.readString(dir.resolve("proc-graceful.log"), StandardCharsets.UTF_8);
            assertTrue(log.contains("registered with"), log);
        } finally {
            processes.forEach(Process::destroyForcibly);
        }
    }

    private static Process launch(String id, int port, Path secretFile, Path log) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = List.of(java, "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                DistributedCli.class.getName(), "worker", "--coordinator", "127.0.0.1:" + port, "--id", id,
                "--secret-file", secretFile.toString(), "--max-concurrent-tasks", "1");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(log.toFile());
        builder.environment().remove("DISTRICOMPUTE_SECRET");
        builder.directory(new File(System.getProperty("java.io.tmpdir")));
        return builder.start();
    }
}
