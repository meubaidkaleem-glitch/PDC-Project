package org.districompute.distributed.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.districompute.core.ErrorCode;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.coordinator.Coordinator;
import org.districompute.distributed.coordinator.CoordinatorConfig;
import org.districompute.distributed.coordinator.TaskDefaults;
import org.districompute.distributed.coordinator.TaskHandle;
import org.districompute.distributed.protocol.CancelPayload;
import org.districompute.distributed.protocol.CancelReason;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ResultPayload;
import org.districompute.distributed.protocol.TaskAcceptedPayload;
import org.districompute.distributed.protocol.TaskPayload;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.testing.ProtocolClient;
import org.districompute.distributed.worker.WorkerConfig;
import org.districompute.distributed.worker.WorkerNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fault tests: real workers crash, go silent, hang, lose attempts or return garbage, and the coordinator detects,
 * retries, quarantines or fails clearly. Timings are short so the suite stays fast.
 */
@Tag("fault")
class FaultToleranceTest {

    private static final SharedSecret SECRET = SharedSecret.of("fault-tolerance-secret!");
    private static final Duration WAIT = Duration.ofSeconds(60);

    private Coordinator coordinator;
    private final List<WorkerNode> workers = new CopyOnWriteArrayList<>();
    private final List<ProtocolClient> scripted = new ArrayList<>();

    @AfterEach
    void stop() throws IOException {
        workers.forEach(WorkerNode::abort);
        for (ProtocolClient client : scripted) {
            client.close();
        }
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void aWorkerCrashDuringATaskIsRecoveredOnAnotherWorker() throws Exception {
        start(TaskDefaults.DEFAULTS);
        WorkerNode victim = worker("victim", 2);
        worker("survivor", 2);
        TaskHandle handle = coordinator.submit(primes("crash", 2_000_000_000L, 8));
        Await.until(Duration.ofSeconds(20), () -> attemptsOn(handle, "victim", AttemptStatus.ACCEPTED,
                AttemptStatus.RUNNING) > 0, "an attempt running on the victim");
        victim.abort();
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status(), String.valueOf(outcome.result().error()));
        assertTrue(outcome.result().verification().passed(), "recovered result verified by Core");
        List<AttemptSnapshot> lost = attempts(outcome, AttemptStatus.LOST);
        assertTrue(!lost.isEmpty() && lost.stream().allMatch(a -> a.workerId().equals("victim")));
        assertTrue(lost.get(0).failure().contains("lost"), lost.get(0).failure());
        assertTrue(outcome.snapshot().retries() >= lost.size());
        assertTrue(outcome.snapshot().metrics().failedAttempts() >= 1);
        assertEquals(WorkerStatus.DISCONNECTED, coordinator.worker("victim").orElseThrow().status());
    }

    @Test
    void missedHeartbeatsDeclareTheWorkerLostAndItsAttemptIsRetried() throws Exception {
        start(TaskDefaults.DEFAULTS);
        ProtocolClient silent = scriptedWorker("silent", 1);
        TaskHandle handle = coordinator.submit(primes("silent-task", 1_000_000, 1));
        Envelope task = silent.receive(MessageType.TASK, WAIT);
        silent.send(accepted(task, "silent"));
        worker("healthy", 1);
        // The silent worker stays connected but sends no heartbeat.
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        AttemptSnapshot first = outcome.snapshot().partitions().get(0).attempts().get(0);
        assertEquals(AttemptStatus.LOST, first.status());
        assertTrue(first.failure().contains("heartbeat timeout"), first.failure());
        assertEquals("healthy", outcome.snapshot().partitions().get(0).attempts().get(1).workerId());
        assertEquals(WorkerStatus.DISCONNECTED, coordinator.worker("silent").orElseThrow().status());
        assertTrue(silent.awaitClosed(Duration.ofSeconds(5)), "the coordinator closes the dead connection");
    }

    @Test
    void aHungAttemptTimesOutIsCancelledAndRetriedElsewhere() throws Exception {
        start(TaskDefaults.DEFAULTS);
        ProtocolClient hung = scriptedWorker("hung", 1);
        TaskHandle handle = coordinator.submit(primes("hung-task", 1_000_000, 1),
                SubmitOptions.DEFAULTS.withAttemptTimeout(Duration.ofMillis(400)));
        Envelope task = hung.receive(MessageType.TASK, WAIT);
        hung.send(accepted(task, "hung"));
        hung.startHeartbeats("hung", 1, 100, () -> List.of(task.attemptId()));
        worker("healthy", 1);
        Envelope cancel = hung.receive(MessageType.CANCEL, WAIT);
        assertEquals(task.attemptId(), cancel.attemptId());
        assertEquals(CancelReason.TIMEOUT, cancel.payload(CancelPayload.class).reason());
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        AttemptSnapshot first = outcome.snapshot().partitions().get(0).attempts().get(0);
        assertEquals(AttemptStatus.TIMED_OUT, first.status());
        assertTrue(first.failure().contains("coordinator timeout"), first.failure());
        assertEquals(1, coordinator.worker("hung").orElseThrow().assignedTasks(),
                "the hung worker's slot stays occupied while it still reports the attempt");
        assertEquals(1, coordinator.worker("hung").orElseThrow().counters().timedOut());
    }

    @Test
    void anAttemptMissingFromTheHeartbeatIsDeclaredLost() throws Exception {
        start(TaskDefaults.DEFAULTS);
        ProtocolClient forgetful = scriptedWorker("forgetful", 1);
        TaskHandle handle = coordinator.submit(primes("forgotten", 1_000_000, 1));
        Envelope task = forgetful.receive(MessageType.TASK, WAIT);
        forgetful.send(accepted(task, "forgetful"));
        worker("healthy", 1);
        forgetful.startHeartbeats("forgetful", 1, 100, List::of);
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        AttemptSnapshot first = outcome.snapshot().partitions().get(0).attempts().get(0);
        assertEquals(AttemptStatus.LOST, first.status());
        assertTrue(first.failure().contains("no longer listed in the heartbeat"), first.failure());
        assertEquals(0, coordinator.worker("forgetful").orElseThrow().assignedTasks(), "its slot is released");
    }

    @Test
    void repeatedInvalidResultsQuarantineTheWorker() throws Exception {
        start(TaskDefaults.DEFAULTS.withQuarantineThreshold(2));
        ProtocolClient liar = scriptedWorker("liar", 2);
        liar.startHeartbeats("liar", 2, 100, List::of);
        TaskHandle handle = coordinator.submit(primes("lies", 2_000_000, 2));
        for (int i = 0; i < 2; i++) {
            Envelope task = liar.receive(MessageType.TASK, WAIT);
            liar.send(corrupted(task, "liar"));
        }
        Await.until(Duration.ofSeconds(5), () -> coordinator.worker("liar").orElseThrow().status()
                == WorkerStatus.QUARANTINED, "quarantine");
        assertTrue(coordinator.worker("liar").orElseThrow().statusReason().contains("2 invalid"));
        worker("honest", 2);
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertTrue(outcome.result().verification().passed());
        assertEquals(2, attempts(outcome, AttemptStatus.INVALID).size());
        assertTrue(attempts(outcome, AttemptStatus.SUCCEEDED).stream().allMatch(a -> a.workerId().equals("honest")),
                "a quarantined worker receives no further attempts");
        assertTrue(attempts(outcome, AttemptStatus.CANCELLED).stream()
                .allMatch(a -> a.failure().contains("quarantined")), "its running attempts were moved");
    }

    @Test
    void theTaskDeadlineEndsALongTaskAndStopsItsKernels() throws Exception {
        start(TaskDefaults.DEFAULTS);
        WorkerNode node = worker("busy", 2);
        TaskSpec spec = TaskSpec.builder("primes.count").taskId("deadline").parameter("lowerBound", 0)
                .parameter("upperBound", 50_000_000_000L).mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(2, null, null, null)).timeoutMillis(500)
                .createdAt(Instant.now()).build();
        TaskOutcome outcome = coordinator.submit(spec).await(WAIT);
        assertEquals(TaskStatus.FAILED, outcome.result().status());
        assertEquals(ErrorCode.TIMEOUT, outcome.result().error().code());
        assertTrue(outcome.result().error().message().contains("500 ms"));
        Await.until(Duration.ofSeconds(10), () -> node.activeTasks() == 0, "the worker's kernels to stop");
    }

    @Test
    void aTaskWithoutACapableWorkerFailsWithAnExplanation() throws Exception {
        start(TaskDefaults.DEFAULTS.withNoWorkerTimeout(Duration.ofMillis(500)));
        workers.add(WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET)
                .workerId("sorter").allowedAlgorithms(Set.of("sort.merge")).build()));
        assertTrue(workers.get(0).awaitRegistered(Duration.ofSeconds(10)));
        TaskOutcome outcome = coordinator.submit(primes("nobody-can", 1_000_000, 2)).await(WAIT);
        assertEquals(TaskStatus.FAILED, outcome.result().status());
        String message = outcome.result().error().message();
        assertTrue(message.contains("No connected worker can run primes.count"), message);
        assertTrue(message.contains("sorter: does not offer primes.count"), message);
    }

    @Test
    void anAttemptTheWorkerNeverConfirmsIsRetried() throws Exception {
        start(TaskDefaults.DEFAULTS.withAcceptTimeout(Duration.ofMillis(400)));
        ProtocolClient mute = scriptedWorker("mute", 1);
        mute.startHeartbeats("mute", 1, 100, List::of);
        TaskHandle handle = coordinator.submit(primes("unconfirmed", 1_000_000, 1));
        mute.receive(MessageType.TASK, WAIT);
        worker("healthy", 1);
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        AttemptSnapshot first = outcome.snapshot().partitions().get(0).attempts().get(0);
        assertEquals(AttemptStatus.LOST, first.status());
        assertTrue(first.failure().contains("no TASK_ACCEPTED within 400 ms"), first.failure());
    }

    @Test
    void pendingTasksAreAssignedWhenAWorkerJoinsLater() throws Exception {
        start(TaskDefaults.DEFAULTS.withNoWorkerTimeout(Duration.ofSeconds(20)));
        TaskHandle handle = coordinator.submit(primes("early", 1_000_000, 3));
        Thread.sleep(300);
        assertEquals(0, handle.snapshot().attempts(), "nothing to dispatch to yet");
        worker("late", 2);
        assertEquals(TaskStatus.COMPLETED, handle.await(WAIT).result().status());
    }

    @Test
    void aDrainingWorkerFinishesItsRunningAttempts() throws Exception {
        start(TaskDefaults.DEFAULTS);
        WorkerNode leaving = worker("leaving", 2);
        TaskHandle handle = coordinator.submit(primes("drain", 1_000_000_000L, 2));
        Await.until(Duration.ofSeconds(20), () -> attemptsOn(handle, "leaving", AttemptStatus.ACCEPTED,
                AttemptStatus.RUNNING) == 2, "both attempts running");
        Thread closer = Thread.ofVirtual().start(leaving::close);
        Await.until(Duration.ofSeconds(10), () -> coordinator.worker("leaving").orElseThrow().status()
                == WorkerStatus.DRAINING, "the worker to announce its shutdown");
        TaskOutcome outcome = handle.await(WAIT);
        closer.join();
        assertEquals(TaskStatus.COMPLETED, outcome.result().status());
        assertEquals(0, outcome.snapshot().retries(), "draining delivers results instead of losing them");
        assertEquals(2, attempts(outcome, AttemptStatus.SUCCEEDED).size());
    }

    @Test
    void aCoordinatorShutdownCancelsRunningTasksAndWorkersReconnect() throws Exception {
        start(TaskDefaults.DEFAULTS);
        int port = coordinator.port();
        WorkerNode node = worker("loyal", 1);
        TaskHandle handle = coordinator.submit(primes("interrupted", 50_000_000_000L, 1));
        Await.until(Duration.ofSeconds(10), () -> handle.snapshot().partitions().stream()
                .anyMatch(p -> p.status() == PartitionStatus.RUNNING), "the attempt to start");
        coordinator.close();
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.CANCELLED, outcome.result().status());
        assertTrue(outcome.result().error().message().contains("SHUTDOWN"), outcome.result().error().message());
        Await.until(Duration.ofSeconds(10), () -> node.activeTasks() == 0, "the worker to stop the kernel");
        coordinator = Coordinator.start(config(port, TaskDefaults.DEFAULTS));
        Await.until(Duration.ofSeconds(15), () -> node.registrations() == 2, "re-registration after the restart");
    }

    @Test
    void repeatedWorkerLossExhaustsTheRetryBudgetAndFailsTheTask() throws Exception {
        start(TaskDefaults.DEFAULTS);
        TaskHandle handle = coordinator.submit(primes("cursed", 1_000_000, 1),
                SubmitOptions.DEFAULTS.withMaxAttempts(3));
        for (int i = 1; i <= 3; i++) {
            ProtocolClient doomed = scriptedWorker("doomed-" + i, 1);
            Envelope task = doomed.receive(MessageType.TASK, WAIT);
            assertEquals("cursed/p0/a" + i, task.attemptId());
            doomed.close();
        }
        TaskOutcome outcome = handle.await(WAIT);
        assertEquals(TaskStatus.FAILED, outcome.result().status(), "repeated failures never become success");
        assertTrue(outcome.result().error().message().contains("retry budget is exhausted"),
                outcome.result().error().message());
        assertEquals(3, attempts(outcome, AttemptStatus.LOST).size());
    }

    private void start(TaskDefaults defaults) throws IOException {
        coordinator = Coordinator.start(config(0, defaults));
    }

    private static CoordinatorConfig config(int port, TaskDefaults defaults) {
        return CoordinatorConfig.builder(SECRET).bindAddress("127.0.0.1").port(port)
                .heartbeat(Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(800))
                .tickInterval(Duration.ofMillis(20)).tasks(defaults).build();
    }

    private WorkerNode worker(String id, int slots) {
        WorkerNode node = WorkerNode.start(WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET).workerId(id)
                .maxConcurrentTasks(slots).backoff(Duration.ofMillis(50), Duration.ofMillis(200))
                .drainTimeout(Duration.ofSeconds(30)).build());
        workers.add(node);
        assertTrue(node.awaitRegistered(Duration.ofSeconds(10)), node.lastError());
        return node;
    }

    private ProtocolClient scriptedWorker(String id, int slots) throws IOException {
        ProtocolClient client = ProtocolClient.connect(coordinator.port());
        scripted.add(client);
        assertEquals(MessageType.REGISTER_ACK, client.register(id, SECRET, CoreFixtures.capabilities(slots)).type());
        return client;
    }

    private static TaskSpec primes(String taskId, long upperBound, int partitions) {
        return TaskSpec.builder("primes.count").taskId(taskId).parameter("lowerBound", 0)
                .parameter("upperBound", upperBound).mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(partitions, null, null, null)).createdAt(Instant.now()).build();
    }

    private static Envelope accepted(Envelope task, String workerId) {
        return Envelope.builder(MessageType.TASK_ACCEPTED).workerId(workerId).attempt(task.attemptRef())
                .payload(new TaskAcceptedPayload(1, 1));
    }

    private static Envelope corrupted(Envelope task, String workerId) {
        var partial = CoreFixtures.execute(task.payload(TaskPayload.class).workUnit(), workerId);
        var payload = partial.payload();
        char[] chars = payload.base64().toCharArray();
        chars[12] = chars[12] == 'A' ? 'B' : 'A';
        var tampered = new org.districompute.core.partition.PartialResultMessage(partial.schemaVersion(),
                partial.taskId(), partial.partitionId(), partial.index(), partial.attempt(), partial.resultType(),
                new org.districompute.core.serialization.EncodedPayload(payload.format(), payload.sizeBytes(),
                        payload.crc32c(), new String(chars)), partial.computeNanos(), workerId);
        return Envelope.builder(MessageType.RESULT).workerId(workerId).attempt(task.attemptRef())
                .payload(new ResultPayload(tampered, new WorkerTiming(0, 0, 0)));
    }

    private static long attemptsOn(TaskHandle handle, String workerId, AttemptStatus... states) {
        Set<AttemptStatus> wanted = Set.of(states);
        return handle.snapshot().partitions().stream().flatMap(p -> p.attempts().stream())
                .filter(a -> a.workerId().equals(workerId) && wanted.contains(a.status())).count();
    }

    private static List<AttemptSnapshot> attempts(TaskOutcome outcome, AttemptStatus status) {
        return outcome.snapshot().partitions().stream().flatMap(p -> p.attempts().stream())
                .filter(a -> a.status() == status).toList();
    }
}
