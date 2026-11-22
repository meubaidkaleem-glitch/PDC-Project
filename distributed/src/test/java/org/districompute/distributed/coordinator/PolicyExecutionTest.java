package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Every scheduling policy drives real workers to a verified result, and records its decisions. */
@Tag("integration")
class PolicyExecutionTest {

    private static final Map<PolicyName, String> PREFIX = Map.of(PolicyName.ROUND_ROBIN, "round robin",
            PolicyName.LEAST_LOADED, "least loaded", PolicyName.CAPABILITY_AWARE, "capability-aware",
            PolicyName.PERFORMANCE_AWARE, "performance-aware", PolicyName.DYNAMIC, "dynamic");

    private static Coordinator coordinator;
    private static final List<WorkerNode> WORKERS = new ArrayList<>();

    @BeforeAll
    static void cluster() throws Exception {
        coordinator = Coordinator.start(CoordinatorRegistrationTest.config()
                .heartbeat(Duration.ofMillis(200), Duration.ofMillis(600), Duration.ofMillis(2000)).build());
        for (int i = 1; i <= 3; i++) {
            WORKERS.add(WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(),
                    CoordinatorRegistrationTest.SECRET).workerId("w" + i).maxConcurrentTasks(i).build()));
        }
        for (WorkerNode worker : WORKERS) {
            assertTrue(worker.awaitRegistered(Duration.ofSeconds(10)), worker.lastError());
        }
    }

    @AfterAll
    static void stop() {
        WORKERS.forEach(WorkerNode::abort);
        coordinator.close();
    }

    @ParameterizedTest
    @EnumSource(PolicyName.class)
    void eachPolicyCompletesATaskAndExplainsEveryAssignment(PolicyName policy) throws Exception {
        TaskOutcome outcome = coordinator.submit(CoreFixtures.primesTask("policy-" + policy, 10_000_000, 12),
                SubmitOptions.DEFAULTS.withPolicy(policy)).await(Duration.ofSeconds(60));
        assertEquals(TaskStatus.COMPLETED, outcome.result().status(), String.valueOf(outcome.result().error()));
        assertEquals(policy, outcome.snapshot().policy());
        List<AttemptSnapshot> attempts = outcome.snapshot().partitions().stream()
                .flatMap(p -> p.attempts().stream()).sorted(Comparator.comparing(a -> a.assignment().decidedAt()))
                .toList();
        assertEquals(12, attempts.size());
        for (AttemptSnapshot attempt : attempts) {
            assertEquals(policy, attempt.assignment().policy());
            assertEquals(attempt.workerId(), attempt.assignment().workerId());
            assertTrue(attempt.assignment().explanation().startsWith(PREFIX.get(policy)),
                    attempt.assignment().explanation());
            assertTrue(attempt.assignment().eligibleWorkers() >= 1);
        }
        if (policy == PolicyName.ROUND_ROBIN) {
            assertEquals(3, attempts.stream().limit(3).map(AttemptSnapshot::workerId).distinct().count(),
                    "the first three assignments rotate over the three workers");
        }
        Await.until(Duration.ofSeconds(5), () -> coordinator.workers().stream().allMatch(w -> w.assignedTasks() == 0),
                "slots to be released");
    }

    @ParameterizedTest
    @EnumSource(value = PolicyName.class, names = {"PERFORMANCE_AWARE", "DYNAMIC"})
    void theDefaultPolicyCanBeChangedAtRunTime(PolicyName policy) throws Exception {
        PolicyName before = coordinator.schedulingPolicy();
        try {
            coordinator.setSchedulingPolicy(policy);
            assertEquals(policy, coordinator.schedulingPolicy());
            TaskOutcome outcome = coordinator.submit(CoreFixtures.primesTask("default-" + policy, 1_000_000, 3))
                    .await(Duration.ofSeconds(60));
            assertEquals(policy, outcome.snapshot().policy());
        } finally {
            coordinator.setSchedulingPolicy(before);
        }
        assertEquals(List.of(PolicyName.values()), coordinator.availablePolicies());
        assertThrows(NullPointerException.class, () -> coordinator.setSchedulingPolicy(null));
    }
}
