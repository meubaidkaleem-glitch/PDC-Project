package org.districompute.distributed.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real workers and a real coordinator over loopback TCP. */
@Tag("integration")
class WorkerCoordinatorTest {

    private static final SharedSecret SECRET = SharedSecret.of("worker-coordinator-secret");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @Test
    void severalWorkersRegisterAndHeartbeatsKeepThemActive() throws IOException {
        try (Coordinator coordinator = Coordinator.start(config(0).build())) {
            List<WorkerNode> workers = new ArrayList<>();
            try {
                for (int i = 1; i <= 3; i++) {
                    workers.add(WorkerNode.start(worker(coordinator.port(), "w" + i, i).build()));
                }
                for (WorkerNode worker : workers) {
                    assertTrue(worker.awaitRegistered(WAIT), worker.lastError());
                }
                // Longer than the heartbeat timeout: only heartbeats keep the workers registered.
                sleep(Duration.ofMillis(1500));
                List<WorkerSnapshot> snapshots = coordinator.workers();
                assertEquals(3, snapshots.size());
                for (WorkerSnapshot snapshot : snapshots) {
                    assertEquals(WorkerStatus.ACTIVE, snapshot.status(), snapshot.workerId());
                    assertNotNull(snapshot.lastHeartbeatAt());
                    assertTrue(snapshot.load().freeMemoryBytes() > 0, "load comes from real heartbeats");
                    assertEquals(Runtime.getRuntime().availableProcessors(), snapshot.capabilities().cpuCores());
                }
                // Workers that start together register in any order; compare by id.
                assertEquals(List.of(1, 2, 3), snapshots.stream()
                        .sorted(java.util.Comparator.comparing(WorkerSnapshot::workerId))
                        .map(WorkerSnapshot::maxConcurrentTasks).toList());
            } finally {
                workers.forEach(WorkerNode::close);
            }
        }
    }

    @Test
    void capacityChangesReachTheCoordinatorThroughHeartbeats() throws IOException {
        try (Coordinator coordinator = Coordinator.start(config(0).build());
                WorkerNode worker = WorkerNode.start(worker(coordinator.port(), "w1", 4).build())) {
            assertTrue(worker.awaitRegistered(WAIT));
            worker.setMaxConcurrentTasks(1);
            Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().maxConcurrentTasks() == 1,
                    "the new capacity to be reported");
        }
    }

    @Test
    void aGracefulShutdownDrainsThenDisconnects() throws IOException {
        try (Coordinator coordinator = Coordinator.start(config(0).build())) {
            WorkerNode worker = WorkerNode.start(worker(coordinator.port(), "w1", 1).build());
            assertTrue(worker.awaitRegistered(WAIT));
            worker.close();
            assertEquals(WorkerNode.State.STOPPED, worker.state());
            Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().status() == WorkerStatus.DISCONNECTED,
                    "the worker to leave");
            assertTrue(coordinator.worker("w1").orElseThrow().statusReason().contains("connection closed"));
        }
    }

    @Test
    void workersComeBackAfterACoordinatorRestart() throws IOException {
        Coordinator first = Coordinator.start(config(0).build());
        int port = first.port();
        WorkerNode worker = WorkerNode.start(worker(port, "w1", 1).build());
        try {
            assertTrue(worker.awaitRegistered(WAIT));
            first.close();
            try (Coordinator second = Coordinator.start(config(port).build())) {
                Await.until(WAIT, () -> worker.registrations() == 2, "re-registration with the new coordinator");
                Await.until(WAIT, () -> second.worker("w1").map(w -> w.status() == WorkerStatus.ACTIVE)
                        .orElse(false), "the new coordinator to list the worker");
            }
        } finally {
            worker.close();
            first.close();
        }
    }

    static CoordinatorConfig.Builder config(int port) {
        return CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(port)
                .heartbeat(Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(800))
                .tickInterval(Duration.ofMillis(20));
    }

    static WorkerConfig.Builder worker(int port, String id, int slots) {
        return WorkerConfig.builder("127.0.0.1", port, SECRET).workerId(id).maxConcurrentTasks(slots)
                .backoff(Duration.ofMillis(50), Duration.ofMillis(200)).drainTimeout(Duration.ofSeconds(5));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
