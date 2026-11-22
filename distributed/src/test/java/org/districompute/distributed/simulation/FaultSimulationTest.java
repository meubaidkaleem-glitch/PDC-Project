package org.districompute.distributed.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.coordinator.TaskDefaults;
import org.districompute.distributed.metrics.WorkerShare;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Real workers with fault profiles, and the runtime's documented reaction to each fault. */
@Tag("fault")
class FaultSimulationTest {

    private static final SharedSecret SECRET = SharedSecret.of("fault-simulation-secret");
    private static final Duration WAIT = Duration.ofSeconds(60);

    private Coordinator coordinator;
    private final List<WorkerNode> workers = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        workers.forEach(WorkerNode::abort);
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void aSlowWorkerGetsFewerPartitionsUnderDynamicScheduling() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("slow", 1, profile().slowdown(4).build());
        worker("fast", 1, FaultProfile.NONE);
        TaskOutcome outcome = coordinator.submit(primes("imbalance", 400_000_000L, 16),
                SubmitOptions.DEFAULTS.withPolicy(PolicyName.DYNAMIC)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(outcome.result().verification().passed());
        int slow = share(outcome, "slow").succeeded();
        int fast = share(outcome, "fast").succeeded();
        assertTrue(fast > slow, "fast " + fast + " vs slow " + slow);
        assertTrue(share(outcome, "slow").kernelNanos() / Math.max(1, slow)
                > 2 * share(outcome, "fast").kernelNanos() / Math.max(1, fast), "the slowdown shows in kernel time");
    }

    @Test
    void aWorkerThatDropsItsConnectionLosesItsAttemptsAndReconnects() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        WorkerNode flaky = worker("dropper", 2, profile().disconnectAfterTasks(2).build());
        worker("steady", 2, FaultProfile.NONE);
        TaskOutcome outcome = coordinator.submit(primes("drops", 200_000_000L, 8)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        Await.until(Duration.ofSeconds(10), () -> flaky.registrations() >= 2, "the dropper to reconnect");
        assertTrue(coordinator.worker("dropper").orElseThrow().counters().lost() >= 1);
    }

    @Test
    void failingKernelsAreRetriedElsewhere() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("failing", 2, profile().failProbability(1).build());
        worker("steady", 2, FaultProfile.NONE);
        TaskOutcome outcome = coordinator.submit(primes("failures", 50_000_000L, 6),
                SubmitOptions.DEFAULTS.withMaxAttempts(3)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(attempts(outcome).stream().filter(a -> a.workerId().equals("failing"))
                .allMatch(a -> a.status() == AttemptStatus.FAILED && a.failure().contains("Simulated kernel failure")));
        assertTrue(attempts(outcome).stream().filter(a -> a.status() == AttemptStatus.SUCCEEDED)
                .allMatch(a -> a.workerId().equals("steady")));
    }

    @Test
    void corruptedResultsAreRejectedAndTheWorkerIsQuarantined() throws Exception {
        start(true, TaskDefaults.DEFAULTS.withQuarantineThreshold(2));
        worker("corrupting", 2, profile().corruptProbability(1).build());
        worker("steady", 2, FaultProfile.NONE);
        TaskOutcome outcome = coordinator.submit(primes("corruption", 50_000_000L, 8)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(outcome.result().verification().passed(), "no corrupted partial was aggregated");
        assertEquals(WorkerStatus.QUARANTINED, coordinator.worker("corrupting").orElseThrow().status());
    }

    @Test
    void duplicateResultsAreCountedNotAggregated() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("echo", 2, profile().duplicateProbability(1).build());
        TaskOutcome outcome = coordinator.submit(primes("echoes", 20_000_000L, 4)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(outcome.result().verification().passed());
        Await.until(Duration.ofSeconds(5), () -> coordinator.worker("echo").orElseThrow().counters()
                .duplicateResults() + coordinator.events().recent(0, 10_000).stream()
                .filter(e -> e.type() == org.districompute.distributed.monitoring.EventType.RESULT_IGNORED).count()
                >= 4, "every duplicate to be classified");
        assertEquals(4, outcome.snapshot().metrics().succeededAttempts());
    }

    @Test
    void hangingAttemptsTimeOutAndAreRetried() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("hanger", 1, profile().hangProbability(1).build());
        worker("steady", 1, FaultProfile.NONE);
        TaskOutcome outcome = coordinator.submit(primes("hangs", 20_000_000L, 2),
                SubmitOptions.DEFAULTS.withAttemptTimeout(Duration.ofMillis(500))).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(attempts(outcome).stream().anyMatch(a -> a.workerId().equals("hanger")
                && a.status() == AttemptStatus.TIMED_OUT));
    }

    @Test
    void stoppedHeartbeatsMakeTheCoordinatorDropTheWorker() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("mute", 1, profile().stopHeartbeatsAfter(200).build());
        Await.until(Duration.ofSeconds(10), () -> coordinator.worker("mute").orElseThrow().status()
                == WorkerStatus.DISCONNECTED, "the heartbeat timeout");
        assertTrue(coordinator.worker("mute").orElseThrow().statusReason().contains("heartbeat timeout"));
    }

    @Test
    void scheduledCapacityChangesReachTheScheduler() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("shrinking", 4, profile().capacity(300, 1).build());
        Await.until(Duration.ofSeconds(10), () -> coordinator.worker("shrinking").orElseThrow()
                .maxConcurrentTasks() == 1, "the capacity change");
    }

    @Test
    void linkLatencyIsMeasuredAsCommunicationNotComputation() throws Exception {
        start(true, TaskDefaults.DEFAULTS);
        worker("remote", 1, profile().messageLatency(80).build());
        TaskOutcome outcome = coordinator.submit(primes("far", 1_000_000L, 2)).await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        for (AttemptSnapshot attempt : attempts(outcome)) {
            assertTrue(attempt.transferNanos() >= 70_000_000L, "transfer " + attempt.transferNanos());
            assertTrue(attempt.computeNanos() < attempt.transferNanos(), "latency is not counted as compute");
        }
    }

    @Test
    void faultSimulationWorkersAreRefusedWithoutOptIn() throws Exception {
        start(false, TaskDefaults.DEFAULTS);
        WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                .workerId("sneaky").faults(profile().slowdown(2).build()).backoff(Duration.ofMillis(50),
                        Duration.ofMillis(100)).build());
        workers.add(node);
        assertFalse(node.awaitRegistered(Duration.ofMillis(800)));
        assertTrue(node.lastError().contains("REGISTRATION_REJECTED"), node.lastError());
        assertTrue(node.capabilities().faultSimulation());
    }

    private void start(boolean allowFaults, TaskDefaults defaults) throws IOException {
        coordinator = Coordinator.start(CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(0)
                .heartbeat(Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(800))
                .tickInterval(Duration.ofMillis(20)).allowFaultSimulationWorkers(allowFaults).tasks(defaults).build());
    }

    private WorkerNode worker(String id, int slots, FaultProfile faults) {
        WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET).workerId(id)
                .maxConcurrentTasks(slots).faults(faults).backoff(Duration.ofMillis(50), Duration.ofMillis(200))
                .build());
        workers.add(node);
        assertTrue(node.awaitRegistered(Duration.ofSeconds(10)), node.lastError());
        return node;
    }

    private static TaskSpec primes(String taskId, long upperBound, int partitions) {
        return TaskSpec.builder("primes.count").taskId(taskId).parameter("lowerBound", 0)
                .parameter("upperBound", upperBound).mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(partitions, null, null, null)).createdAt(Instant.now()).build();
    }

    private static List<AttemptSnapshot> attempts(TaskOutcome outcome) {
        return outcome.snapshot().partitions().stream().flatMap(p -> p.attempts().stream()).toList();
    }

    private static WorkerShare share(TaskOutcome outcome, String workerId) {
        return outcome.snapshot().metrics().perWorker().stream().filter(s -> s.workerId().equals(workerId))
                .findFirst().orElse(new WorkerShare(workerId, 0, 0, 0, 0, 0, 0));
    }

    private static ProfileBuilder profile() {
        return new ProfileBuilder();
    }

    /** Small builder for readable profiles in tests. */
    private static final class ProfileBuilder {
        private double slowdown = 1;
        private int disconnectAfter;
        private double fail;
        private double corrupt;
        private double duplicate;
        private double hang;
        private long stopHeartbeats;
        private long latency;
        private List<FaultProfile.CapacityChange> schedule = List.of();

        ProfileBuilder slowdown(double value) {
            slowdown = value;
            return this;
        }

        ProfileBuilder disconnectAfterTasks(int value) {
            disconnectAfter = value;
            return this;
        }

        ProfileBuilder failProbability(double value) {
            fail = value;
            return this;
        }

        ProfileBuilder corruptProbability(double value) {
            corrupt = value;
            return this;
        }

        ProfileBuilder duplicateProbability(double value) {
            duplicate = value;
            return this;
        }

        ProfileBuilder hangProbability(double value) {
            hang = value;
            return this;
        }

        ProfileBuilder stopHeartbeatsAfter(long millis) {
            stopHeartbeats = millis;
            return this;
        }

        ProfileBuilder messageLatency(long millis) {
            latency = millis;
            return this;
        }

        ProfileBuilder capacity(long afterMillis, int capacity) {
            schedule = List.of(new FaultProfile.CapacityChange(afterMillis, capacity));
            return this;
        }

        FaultProfile build() {
            return new FaultProfile(true, 1, 0, latency, slowdown, disconnectAfter, stopHeartbeats, hang, fail,
                    corrupt, duplicate, schedule);
        }
    }
}
