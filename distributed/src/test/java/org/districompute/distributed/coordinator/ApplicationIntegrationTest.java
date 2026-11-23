package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.scheduling.Calibration;
import org.districompute.core.scheduling.StrategyAssessment;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.monitoring.EventBus;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.monitoring.RuntimeEvent;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.strategy.ExecutionAdvice;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Drives the coordinator the way DistriCompute-Application would: ask for advice, submit in every mode, override
 * the recommendation, follow events, read results and metrics.
 */
@Tag("integration")
class ApplicationIntegrationTest {

    private static final SharedSecret SECRET = SharedSecret.of("application-integration");

    @Test
    void theApplicationWorkflowFromAdviceToResults() throws Exception {
        try (Coordinator coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1")
                .port(0).heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000))
                .tickInterval(Duration.ofMillis(20)).build())) {
            List<RuntimeEvent> events = new CopyOnWriteArrayList<>();
            EventBus.Subscription subscription = coordinator.events().subscribe(events::add, 0);
            TaskSpec request = primes("advice-only", ExecutionMode.DISTRIBUTED_CPU);

            // 1. Without workers, distributed execution is not possible, and the advice says why.
            ExecutionAdvice lonely = coordinator.advise(request);
            assertEquals(ExecutionMode.MULTITHREADED_CPU, lonely.recommended(), lonely.explanation());
            StrategyAssessment distributed = lonely.assessment().assessment(ExecutionMode.DISTRIBUTED_CPU)
                    .orElseThrow();
            assertEquals(StrategyAssessment.Verdict.UNSUITABLE, distributed.verdict());
            assertTrue(distributed.reasons().get(0).contains("No workers registered"), distributed.reasons().get(0));
            assertFalse(lonely.runtime().executableModes().contains(ExecutionMode.DISTRIBUTED_CPU));
            assertTrue(lonely.runtime().notes().stream().anyMatch(n -> n.contains("No connected worker")));
            assertNull(lonely.resources().network(), "nothing measured yet");

            List<WorkerNode> workers = new ArrayList<>();
            try {
                for (String id : List.of("app-w1", "app-w2")) {
                    WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                            .workerId(id).maxConcurrentTasks(2).build());
                    workers.add(node);
                    assertTrue(node.awaitRegistered(Duration.ofSeconds(10)));
                }

                // 2. With workers, distributed execution becomes suitable; the runtime supplied them to Core.
                ExecutionAdvice withWorkers = coordinator.advise(request);
                assertEquals(List.of("app-w1", "app-w2"), withWorkers.runtime().eligibleWorkers());
                assertEquals(4, withWorkers.runtime().totalSlots());
                assertEquals(2, withWorkers.resources().workers().size());
                assertEquals(StrategyAssessment.Verdict.SUITABLE, withWorkers.assessment()
                        .assessment(ExecutionMode.DISTRIBUTED_CPU).orElseThrow().verdict());
                assertTrue(withWorkers.runtime().executableModes().contains(ExecutionMode.DISTRIBUTED_CPU));
                assertTrue(withWorkers.explanation().startsWith("Recommended: MULTITHREADED_CPU"),
                        withWorkers.explanation());

                // 3. A manual override runs, and is recorded and published.
                TaskOutcome remote = coordinator.submit(primes("override", ExecutionMode.DISTRIBUTED_CPU),
                        SubmitOptions.DEFAULTS.withOverrideReason("demonstrate the cluster"))
                        .await(Duration.ofSeconds(60));
                assertEquals(TaskStatus.COMPLETED, remote.result().status());
                assertNotNull(remote.snapshot().override());
                assertEquals(ExecutionMode.MULTITHREADED_CPU, remote.snapshot().override().recommended());
                assertEquals(ExecutionMode.DISTRIBUTED_CPU, remote.snapshot().override().chosen());
                assertEquals("demonstrate the cluster", remote.snapshot().override().reason());
                Await.until(Duration.ofSeconds(5), () -> events.stream()
                        .anyMatch(e -> e.type() == EventType.STRATEGY_OVERRIDE), "the override event");

                // 4. After a distributed run, the network has been measured and is supplied to Core.
                ExecutionAdvice measured = coordinator.advise(request);
                assertNotNull(measured.resources().network());
                assertNotNull(measured.runtime().latencyMillis());
                assertTrue(measured.runtime().transferBytesPerSecond() > 0);

                // 5. Sequential and local parallel requests run through Core's local engine.
                TaskOutcome sequential = coordinator.submit(primes("local-seq", ExecutionMode.SEQUENTIAL_CPU))
                        .await(Duration.ofSeconds(60));
                TaskOutcome parallel = coordinator.submit(primes("local-mt", ExecutionMode.MULTITHREADED_CPU))
                        .await(Duration.ofSeconds(60));
                for (TaskOutcome outcome : List.of(sequential, parallel)) {
                    assertEquals(TaskStatus.COMPLETED, outcome.result().status(),
                            outcome.result().mode() + ": " + outcome.result().error());
                    assertTrue(outcome.result().verification().passed());
                    assertNull(outcome.snapshot().metrics(), "no distributed metrics for local runs");
                    assertTrue(outcome.snapshot().partitions().isEmpty());
                }
                assertEquals(ExecutionMode.SEQUENTIAL_CPU, sequential.result().mode());
                assertEquals(ExecutionMode.MULTITHREADED_CPU, parallel.result().mode());
                assertEquals(remote.result().result().digest(), sequential.result().result().digest(),
                        "the same result in every mode");
                assertEquals(sequential.result().result().digest(), parallel.result().result().digest());
                assertEquals(TaskStatus.COMPLETED, coordinator.task("local-seq").orElseThrow().status());

                // 6. Measured runs calibrate Core's cost model: advice now carries time estimates.
                assertTrue(coordinator.calibrations().stream().anyMatch(c -> c.mode() == ExecutionMode.SEQUENTIAL_CPU));
                ExecutionAdvice calibrated = coordinator.advise(request);
                assertTrue(calibrated.assessment().calibrated());
                assertNotNull(calibrated.assessment().assessment(ExecutionMode.SEQUENTIAL_CPU).orElseThrow()
                        .estimatedSeconds());

                // 7. Modes that do not exist are refused with an explanation.
                DistriComputeException gpu = assertThrows(DistriComputeException.class,
                        () -> coordinator.submit(primes("gpu", ExecutionMode.GPU)));
                assertEquals(ErrorCode.UNSUPPORTED_EXECUTION_MODE, gpu.code());
                assertTrue(gpu.getMessage().contains("no worker reports a working GPU backend"), gpu.getMessage());
                assertTrue(assertThrows(DistriComputeException.class,
                        () -> coordinator.submit(primes("hybrid", ExecutionMode.HYBRID))).getMessage()
                        .contains("not implemented"));

                // 8. The Application can supply its own benchmark history.
                coordinator.addCalibrations(List.of(new Calibration(AlgorithmId.of("sort.merge"),
                        ExecutionMode.SEQUENTIAL_CPU, 1, 2.5e7, 1e8)));
                assertTrue(coordinator.calibrations().stream()
                        .anyMatch(c -> c.algorithm().equals(AlgorithmId.of("sort.merge"))));

                // Every task is visible, in one list, with the events to follow it.
                assertEquals(3, coordinator.tasks().size());
                Await.until(Duration.ofSeconds(5), () -> events.stream().filter(e -> e.type()
                        == EventType.TASK_FINISHED).count() == 3, "three finished events");
                assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TASK_STATUS_CHANGED
                        && "local-mt".equals(e.taskId()) && "RUNNING".equals(e.details().get("to"))));
            } finally {
                subscription.close();
                workers.forEach(WorkerNode::abort);
            }
        }
    }

    private static TaskSpec primes(String taskId, ExecutionMode mode) {
        TaskSpec.Builder builder = TaskSpec.builder("primes.count").taskId(taskId).experimentId("app-test")
                .parameter("lowerBound", 0).parameter("upperBound", 50_000_000).mode(mode).createdAt(Instant.now());
        // Core accepts a thread count only for MULTITHREADED_CPU.
        return mode == ExecutionMode.MULTITHREADED_CPU ? builder.maxThreads(2).build() : builder.build();
    }
}
