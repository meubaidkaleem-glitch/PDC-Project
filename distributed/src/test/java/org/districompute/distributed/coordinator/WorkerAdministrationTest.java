package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.monitoring.RuntimeEvent;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Operators deactivate, activate, disconnect, stop and forget real workers. */
@Tag("integration")
class WorkerAdministrationTest {

    private static final SharedSecret SECRET = SharedSecret.of("worker-administration-secret");
    private static final Duration WAIT = Duration.ofSeconds(30);

    private Coordinator coordinator;
    private final List<WorkerNode> workers = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(100), Duration.ofMillis(400), Duration.ofMillis(1500))
                .tickInterval(Duration.ofMillis(20)).build());
        for (int i = 1; i <= 2; i++) {
            workers.add(WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                    .workerId("w" + i).maxConcurrentTasks(2).backoff(Duration.ofMillis(50), Duration.ofMillis(200))
                    .build()));
        }
        for (WorkerNode worker : workers) {
            assertTrue(worker.awaitRegistered(Duration.ofSeconds(10)), worker.lastError());
        }
    }

    @AfterEach
    void stop() {
        workers.forEach(WorkerNode::abort);
        coordinator.close();
    }

    @Test
    void aDeactivatedWorkerReceivesNoAttemptsUntilItIsActivated() throws Exception {
        assertTrue(coordinator.deactivateWorker("w1", "maintenance"));
        WorkerSnapshot w1 = coordinator.worker("w1").orElseThrow();
        assertEquals(WorkerStatus.DEACTIVATED, w1.status());
        assertTrue(w1.statusReason().contains("maintenance"), w1.statusReason());

        // Heartbeats keep arriving, but do not reactivate it.
        Thread.sleep(500);
        assertEquals(WorkerStatus.DEACTIVATED, coordinator.worker("w1").orElseThrow().status());

        TaskOutcome outcome = coordinator.submit(CoreFixtures.primesTask("admin-1", 5_000_000, 8)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertEquals(Set.of("w2"), workersUsed(outcome));

        assertTrue(coordinator.activateWorker("w1"));
        assertEquals(WorkerStatus.ACTIVE, coordinator.worker("w1").orElseThrow().status());
        TaskOutcome after = coordinator.submit(CoreFixtures.primesTask("admin-2", 5_000_000, 8)).await(WAIT);
        assertEquals(Set.of("w1", "w2"), workersUsed(after));

        List<RuntimeEvent> changes = coordinator.events().recent(0, 1000).stream()
                .filter(e -> e.type() == EventType.WORKER_STATUS_CHANGED && "w1".equals(e.workerId())).toList();
        assertTrue(changes.stream().anyMatch(e -> "DEACTIVATED".equals(e.details().get("to"))), changes.toString());
        assertTrue(changes.stream().anyMatch(e -> "ACTIVE".equals(e.details().get("to"))), changes.toString());
    }

    @Test
    void aDeactivationSurvivesReconnection() {
        assertTrue(coordinator.deactivateWorker("w1", "suspected faulty memory"));
        assertTrue(coordinator.disconnectWorker("w1", "reconnect test", false));
        Await.until(WAIT, () -> coordinator.worker("w1").orElseThrow().registrations() == 2
                && coordinator.worker("w1").orElseThrow().status().isConnected(), "w1 to reconnect");
        assertEquals(WorkerStatus.DEACTIVATED, coordinator.worker("w1").orElseThrow().status());
        assertTrue(coordinator.activateWorker("w1"));
        assertEquals(WorkerStatus.ACTIVE, coordinator.worker("w1").orElseThrow().status());
    }

    @Test
    void aStoppedWorkerExitsAndCanThenBeForgotten() {
        assertFalse(coordinator.forgetWorker("w1"), "a connected worker cannot be forgotten");
        assertTrue(coordinator.disconnectWorker("w1", "decommissioned", true));
        assertTrue(workers.get(0).awaitStopped(WAIT), "the worker process stops instead of reconnecting");
        WorkerSnapshot w1 = coordinator.worker("w1").orElseThrow();
        assertEquals(WorkerStatus.DISCONNECTED, w1.status());
        assertTrue(w1.statusReason().contains("decommissioned"), w1.statusReason());
        assertFalse(coordinator.disconnectWorker("w1", "again", true), "no longer connected");
        assertTrue(coordinator.forgetWorker("w1"));
        assertTrue(coordinator.worker("w1").isEmpty());
        assertFalse(coordinator.forgetWorker("w1"));
        assertFalse(coordinator.deactivateWorker("unknown", "x"));
        assertFalse(coordinator.activateWorker("unknown"));
    }

    private static Set<String> workersUsed(TaskOutcome outcome) {
        return outcome.snapshot().partitions().stream().flatMap(p -> p.attempts().stream())
                .map(a -> a.workerId()).collect(Collectors.toSet());
    }
}
