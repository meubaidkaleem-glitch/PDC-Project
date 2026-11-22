package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmInfo;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.metrics.DistributedMetrics;
import org.districompute.distributed.metrics.WorkerShare;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.PartitionSnapshot;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.task.TaskSnapshot;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Tasks dispatched to real workers over loopback TCP and aggregated through Core. */
@Tag("integration")
class DistributedExecutionTest {

    static final SharedSecret SECRET = SharedSecret.of("distributed-execution-secret");
    static final Duration WAIT = Duration.ofSeconds(60);
    /** Core documents these as equal to sequential only within rounding (floating-point reductions). */
    private static final Set<String> ROUNDING = Set.of("numeric.integrate", "data.statistics");

    private Coordinator coordinator;
    private final List<WorkerNode> workers = new ArrayList<>();

    @AfterEach
    void stop() {
        workers.forEach(WorkerNode::abort);
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void everyDistributableAlgorithmRunsOnOneWorkerAndMatchesSequential() throws Exception {
        cluster(1, 2);
        runCatalog();
    }

    @Test
    void everyDistributableAlgorithmRunsOnThreeWorkersAndMatchesSequential() throws Exception {
        cluster(3, 2);
        runCatalog();
    }

    @Test
    void theSnapshotRecordsPartitionsAttemptsDecisionsAndMetrics() throws Exception {
        cluster(3, 2);
        TaskSpec spec = CoreFixtures.primesTask("primes-12", 20_000_000, 12);
        TaskOutcome outcome = coordinator.submit(spec).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status(), String.valueOf(outcome.result().error()));
        TaskSnapshot snapshot = outcome.snapshot();
        assertEquals(12, snapshot.totalPartitions());
        assertEquals(12, snapshot.completedPartitions());
        assertEquals(12, snapshot.attempts());
        assertEquals(0, snapshot.retries());
        assertEquals(PolicyName.LEAST_LOADED, snapshot.policy());
        for (PartitionSnapshot partition : snapshot.partitions()) {
            assertEquals(PartitionStatus.COMPLETED, partition.status());
            AttemptSnapshot attempt = partition.attempts().get(0);
            assertEquals(partition.partitionId() + "/a1", attempt.attemptId());
            assertEquals(AttemptStatus.SUCCEEDED, attempt.status());
            assertEquals(attempt.workerId(), attempt.assignment().workerId());
            assertTrue(attempt.assignment().explanation().startsWith("least loaded"),
                    attempt.assignment().explanation());
            assertTrue(attempt.computeNanos() > 0 && attempt.workerExecuteNanos() >= attempt.computeNanos());
            assertTrue(attempt.roundTripNanos() >= attempt.transferNanos() && attempt.transferNanos() >= 0);
            assertTrue(attempt.bytesSent() > 0 && attempt.bytesReceived() > 0);
            assertNotNull(attempt.acceptedAt());
        }
        DistributedMetrics metrics = snapshot.metrics();
        assertEquals(3, metrics.workersUsed(), "12 partitions on 3 workers with 2 slots each");
        assertEquals(12, metrics.perWorker().stream().mapToInt(WorkerShare::succeeded).sum());
        assertTrue(metrics.kernelNanos() > 0 && metrics.transferNanos() >= 0 && metrics.bytesSent() > 0);
        assertTrue(metrics.distributedNanos() > 0 && metrics.totalNanos() >= metrics.distributedNanos());
        assertTrue(metrics.workerUtilization() > 0 && metrics.throughputPartitionsPerSecond() > 0);
        assertTrue(metrics.loadImbalance() >= 1.0);
        assertEquals(0, metrics.failedAttempts());
        Await.until(Duration.ofSeconds(5), () -> coordinator.workers().stream().allMatch(w -> w.assignedTasks() == 0),
                "every slot to be free again");
        assertEquals(12, coordinator.workers().stream().mapToLong(w -> w.counters().succeeded()).sum());
        assertTrue(coordinator.workers().stream().allMatch(w -> !w.performance().isEmpty()),
                "every worker has observed performance for primes.count");
    }

    @Test
    void aCancelledTaskStopsItsRunningAttempts() throws Exception {
        cluster(2, 1);
        TaskSpec slow = TaskSpec.builder("primes.count").taskId("slow").parameter("lowerBound", 0)
                .parameter("upperBound", 50_000_000_000L).mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(4, null, null, null)).createdAt(Instant.now()).build();
        TaskHandle handle = coordinator.submit(slow);
        Await.until(Duration.ofSeconds(10), () -> handle.snapshot().partitions().stream()
                .anyMatch(p -> p.status() == PartitionStatus.RUNNING), "attempts to start");
        assertTrue(handle.cancel());
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.CANCELLED, outcome.result().status());
        assertEquals(ErrorCode.CANCELLED, outcome.result().error().code());
        assertFalse(handle.cancel(), "cancelling twice is a no-op");
        Await.until(Duration.ofSeconds(10), () -> workers.stream().allMatch(w -> w.activeTasks() == 0),
                "the workers' kernels to stop");
        Await.until(Duration.ofSeconds(10), () -> coordinator.workers().stream()
                .allMatch(w -> w.assignedTasks() == 0), "the coordinator to free every slot");
    }

    @Test
    void invalidTasksFailWithCoresErrorAndRefusedSubmissionsThrow() throws Exception {
        cluster(1, 1);
        TaskSpec invalid = TaskSpec.builder("matrix.multiply").taskId("bad-dims").parameter("rowsA", 4)
                .parameter("colsA", -3).parameter("colsB", 4).mode(ExecutionMode.DISTRIBUTED_CPU).build();
        TaskResult result = coordinator.submit(invalid).await(WAIT).result();
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals(ErrorCode.VALIDATION_FAILED, result.error().code());
        assertFalse(result.error().violations().isEmpty());

        TaskSpec local = TaskSpec.builder("primes.count").taskId("local").mode(ExecutionMode.SEQUENTIAL_CPU).build();
        DistriComputeException mode = assertThrows(DistriComputeException.class, () -> coordinator.submit(local));
        assertEquals(ErrorCode.UNSUPPORTED_EXECUTION_MODE, mode.code());
        assertThrows(IllegalArgumentException.class, () -> coordinator.submit(invalid), "task ids are unique");
        DistriComputeException unknown = assertThrows(DistriComputeException.class, () -> coordinator.submit(
                TaskSpec.builder("no.such.algorithm").mode(ExecutionMode.DISTRIBUTED_CPU).build()));
        assertEquals(ErrorCode.UNKNOWN_ALGORITHM, unknown.code());
    }

    private void runCatalog() throws Exception {
        List<AlgorithmInfo> distributable = CoreFixtures.registry().catalog().stream()
                .filter(info -> info.capabilities().distributable()).toList();
        assertEquals(10, distributable.size());
        Map<String, TaskHandle> handles = distributable.stream().collect(Collectors.toMap(
                info -> info.descriptor().id().value(), info -> coordinator.submit(TaskSpec.builder(
                        info.descriptor().id().value()).mode(ExecutionMode.DISTRIBUTED_CPU).build())));
        try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
            for (Map.Entry<String, TaskHandle> entry : handles.entrySet()) {
                TaskResult result = entry.getValue().await(WAIT).result();
                assertEquals(TaskStatus.COMPLETED, result.status(), entry.getKey() + ": " + result.error());
                assertTrue(result.verification().passed(), entry.getKey());
                assertEquals(ExecutionMode.DISTRIBUTED_CPU, result.mode());
                if (!ROUNDING.contains(entry.getKey())) {
                    TaskResult sequential = engine.execute(TaskSpec.builder(entry.getKey())
                            .mode(ExecutionMode.SEQUENTIAL_CPU).build()).taskResult();
                    assertEquals(sequential.result().digest(), result.result().digest(),
                            entry.getKey() + " is bitwise identical to sequential");
                }
            }
        }
    }

    private void cluster(int count, int slots) throws IOException {
        coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000))
                .tickInterval(Duration.ofMillis(20)).build());
        for (int i = 1; i <= count; i++) {
            workers.add(WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                    .workerId("w" + i).maxConcurrentTasks(slots).backoff(Duration.ofMillis(50),
                            Duration.ofMillis(200)).build()));
        }
        for (WorkerNode worker : workers) {
            assertTrue(worker.awaitRegistered(Duration.ofSeconds(10)), worker.lastError());
        }
        Await.until(Duration.ofSeconds(10), () -> coordinator.workers().size() == count, "all workers listed");
    }
}
