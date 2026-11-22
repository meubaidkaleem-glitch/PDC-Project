package org.districompute.distributed.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.districompute.core.ErrorCode;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.partition.WorkUnitMessage;
import org.districompute.core.result.PartialResultCollector;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskPriority;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.CancelPayload;
import org.districompute.distributed.protocol.CancelReason;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.protocol.Ids;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.RegisterPayload;
import org.districompute.distributed.protocol.ResultPayload;
import org.districompute.distributed.protocol.ShutdownPayload;
import org.districompute.distributed.protocol.TaskPayload;
import org.districompute.distributed.protocol.TaskProgressPayload;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.security.SharedSecret;
import org.districompute.distributed.testing.Await;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.testing.ProtocolServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A real worker driven by a scripted coordinator over loopback TCP. */
@Tag("integration")
class WorkerNodeTest {

    private static final SharedSecret SECRET = SharedSecret.of("worker-test-secret-123");
    private static final Duration WAIT = Duration.ofSeconds(10);

    private ProtocolServer coordinator;
    private WorkerNode worker;

    @BeforeEach
    void listen() throws Exception {
        coordinator = new ProtocolServer();
    }

    @AfterEach
    void stop() throws Exception {
        if (worker != null) {
            worker.abort();
        }
        coordinator.close();
    }

    @Test
    void reportsRealCapabilities() throws Exception {
        start(config().label("room", "lab-2").maxConcurrentTasks(3));
        WorkerCapabilities capabilities = coordinator.acceptRegistration(SECRET, SECRET, 100)
                .payload(RegisterPayload.class).capabilities();
        assertEquals(Runtime.getRuntime().availableProcessors(), capabilities.cpuCores());
        assertEquals(Runtime.getRuntime().maxMemory(), capabilities.maxHeapBytes());
        assertEquals(3, capabilities.maxConcurrentTasks());
        assertEquals(10, capabilities.algorithms().size(), "every distributable Core algorithm");
        assertTrue(capabilities.algorithms().stream().allMatch(a -> a.supports(ExecutionMode.DISTRIBUTED_CPU)));
        assertFalse(capabilities.gpu().available(), "no GPU without a detected Core backend");
        assertFalse(capabilities.gpu().unavailableReasons().isEmpty());
        assertEquals("lab-2", capabilities.labels().get("room"));
        assertFalse(capabilities.faultSimulation());
        assertTrue(worker.awaitRegistered(WAIT));
    }

    @Test
    void executesCoreWorkUnitsAndCoreAcceptsEveryResult() throws Exception {
        start(config().maxConcurrentTasks(2));
        coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));
        TaskSpec spec = CoreFixtures.primesTask("primes-remote", 3_000_000, 4);
        PartitionedTaskSession session = CoreFixtures.session(spec);
        for (int batch = 0; batch < 4; batch += 2) {
            List<AttemptRef> refs = List.of(dispatch(session, batch, 1, 60_000), dispatch(session, batch + 1, 1,
                    60_000));
            for (AttemptRef ref : refs) {
                assertEquals(MessageType.TASK_ACCEPTED, coordinator.receiveFor(ref.attemptId(), WAIT).type());
            }
            for (AttemptRef ref : refs) {
                Envelope result = coordinator.receiveFor(ref.attemptId(), WAIT);
                assertEquals(MessageType.RESULT, result.type(), result.toString());
                ResultPayload payload = result.payload(ResultPayload.class);
                WorkerTiming timing = payload.timing();
                assertTrue(timing.residenceNanos() >= timing.executeNanos());
                assertTrue(timing.executeNanos() >= payload.partialResult().computeNanos());
                assertEquals(PartialResultCollector.Classification.ACCEPTED,
                        session.accept(payload.partialResult()).classification());
            }
        }
        TaskResult result = session.complete();
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertTrue(result.verification().passed());
        TaskSpec sequential = TaskSpec.builder("primes.count").parameter("lowerBound", 0)
                .parameter("upperBound", 3_000_000).mode(ExecutionMode.SEQUENTIAL_CPU).build();
        try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
            assertEquals(engine.execute(sequential).taskResult().result().digest(), result.result().digest(),
                    "remote execution is bitwise identical to sequential");
        }
        assertEquals(4, worker.completedTasks());
    }

    @Test
    void refusesWorkItCannotOrMustNotRun() throws Exception {
        start(config().maxConcurrentTasks(1).allowedAlgorithms(Set.of("primes.count")));
        coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));

        TaskSpec sort = TaskSpec.builder("sort.merge").taskId("sort-1").parameter("size", 1000)
                .mode(ExecutionMode.DISTRIBUTED_CPU).createdAt(Instant.now()).build();
        AttemptRef sortRef = dispatch(CoreFixtures.session(sort), 0, 1, 60_000);
        assertRejected(coordinator.receiveFor(sortRef.attemptId(), WAIT), true, "not offered");

        PartitionedTaskSession slow = CoreFixtures.session(longPrimes("slow-1"));
        AttemptRef running = dispatch(slow, 0, 1, 60_000);
        assertEquals(MessageType.TASK_ACCEPTED, coordinator.receiveFor(running.attemptId(), WAIT).type());
        AttemptRef busy = dispatch(CoreFixtures.session(longPrimes("slow-2")), 0, 1, 60_000);
        assertRejected(coordinator.receiveFor(busy.attemptId(), WAIT), true, "limit of 1");

        Envelope duplicate = task(slow, 0, 1, 60_000);
        coordinator.send(duplicate);
        assertRejected(coordinator.receiveFor(running.attemptId(), WAIT), false, "Duplicate attempt");
        cancel(running);
        assertEquals(ProtocolErrorCode.TASK_CANCELLED, error(coordinator.receiveFor(running.attemptId(), WAIT))
                .code());
    }

    @Test
    void cancellationStopsARunningKernel() throws Exception {
        start(config());
        coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));
        AttemptRef ref = dispatch(CoreFixtures.session(longPrimes("long-1")), 0, 1, 600_000);
        assertEquals(MessageType.TASK_ACCEPTED, coordinator.receiveFor(ref.attemptId(), WAIT).type());
        HeartbeatPayload beat = coordinator.receive(MessageType.HEARTBEAT, WAIT).payload(HeartbeatPayload.class);
        assertEquals(List.of(ref.attemptId()), beat.runningAttempts(), "heartbeats continue while kernels run");
        long start = System.nanoTime();
        cancel(ref);
        ErrorPayload error = error(coordinator.receiveFor(ref.attemptId(), WAIT));
        assertEquals(ProtocolErrorCode.TASK_CANCELLED, error.code());
        assertFalse(error.retryable());
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(3).toNanos(), "cooperative cancellation is prompt");
        Await.until(WAIT, () -> worker.activeTasks() == 0, "the slot to be freed");
    }

    @Test
    void theAttemptTimeoutCancelsTheKernelAndReportsARetryableTimeout() throws Exception {
        start(config());
        coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));
        AttemptRef ref = dispatch(CoreFixtures.session(longPrimes("long-2")), 0, 1, 300);
        assertEquals(MessageType.TASK_ACCEPTED, coordinator.receiveFor(ref.attemptId(), WAIT).type());
        ErrorPayload error = error(coordinator.receiveFor(ref.attemptId(), WAIT));
        assertEquals(ProtocolErrorCode.TASK_FAILED, error.code());
        assertTrue(error.retryable());
        assertEquals(ErrorCode.TIMEOUT, error.taskError().code());
        assertEquals(0, error.taskError().partitionIndex());
    }

    @Test
    void kernelProgressIsReportedAndThrottled() throws Exception {
        start(config().progressInterval(Duration.ofMillis(50)));
        coordinator.acceptRegistration(SECRET, SECRET, 1000);
        assertTrue(worker.awaitRegistered(WAIT));
        AttemptRef ref = dispatch(CoreFixtures.session(longPrimes("long-3")), 0, 1, 600_000);
        TaskProgressPayload first = coordinator.receive(MessageType.TASK_PROGRESS, WAIT)
                .payload(TaskProgressPayload.class);
        TaskProgressPayload second = coordinator.receive(MessageType.TASK_PROGRESS, WAIT)
                .payload(TaskProgressPayload.class);
        assertTrue(second.completedUnits() > first.completedUnits());
        // Core's partition kernels report advances only; the total is 0, which the protocol defines as unknown.
        assertEquals(0, first.totalUnits());
        cancel(ref);
    }

    @Test
    void refusesToWorkForACoordinatorThatCannotProveTheSecret() throws Exception {
        start(config());
        coordinator.acceptRegistration(SECRET, SharedSecret.of("an-impostor-secret-xyz"), 100);
        assertTrue(coordinator.awaitClosed(WAIT), "the worker hangs up");
        assertFalse(worker.awaitRegistered(Duration.ofMillis(300)));
        assertTrue(worker.lastError().contains("could not prove"), worker.lastError());
    }

    @Test
    void reconnectsAndRegistersAgainAfterTheConnectionIsLost() throws Exception {
        start(config());
        Envelope first = coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));
        coordinator.dropConnection();
        coordinator.accept(WAIT);
        Envelope second = coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertFalse(first.payload(RegisterPayload.class).proof().nonce()
                .equals(second.payload(RegisterPayload.class).proof().nonce()), "a fresh proof each time");
        Await.until(WAIT, () -> worker.registrations() == 2, "the second registration");
    }

    @Test
    void stopsWhenTheCoordinatorEndsTheCluster() throws Exception {
        start(config());
        coordinator.acceptRegistration(SECRET, SECRET, 100);
        assertTrue(worker.awaitRegistered(WAIT));
        coordinator.send(Envelope.builder(MessageType.SHUTDOWN).payload(new ShutdownPayload("end", false, false)));
        assertTrue(worker.awaitStopped(WAIT));
        assertEquals(WorkerNode.State.STOPPED, worker.state());
    }

    private WorkerConfig.Builder config() {
        return WorkerConfig.builder("127.0.0.1", coordinator.port(), SECRET).workerId("test-worker")
                .backoff(Duration.ofMillis(50), Duration.ofMillis(200)).drainTimeout(Duration.ofSeconds(5));
    }

    private void start(WorkerConfig.Builder builder) throws Exception {
        worker = WorkerNode.start(builder.build());
        coordinator.accept(WAIT);
    }

    /** One partition whose kernel runs for many seconds unless cancelled. */
    private static TaskSpec longPrimes(String taskId) {
        return TaskSpec.builder("primes.count").taskId(taskId).parameter("lowerBound", 0)
                .parameter("upperBound", 50_000_000_000L).mode(ExecutionMode.DISTRIBUTED_CPU)
                .partitioning(new PartitioningOptions(1, null, null, null)).createdAt(Instant.now()).build();
    }

    private Envelope task(PartitionedTaskSession session, int index, int attempt, long timeoutMillis) {
        WorkUnitMessage unit = session.workUnit(index, attempt);
        String partitionId = unit.descriptor().partitionId();
        AttemptRef ref = new AttemptRef("exp", unit.descriptor().taskId(), partitionId,
                Ids.attemptId(partitionId, attempt));
        return Envelope.builder(MessageType.TASK).workerId("test-worker").attempt(ref)
                .payload(new TaskPayload(unit, timeoutMillis, TaskPriority.NORMAL));
    }

    private AttemptRef dispatch(PartitionedTaskSession session, int index, int attempt, long timeoutMillis) {
        Envelope task = task(session, index, attempt, timeoutMillis);
        coordinator.send(task);
        return task.attemptRef();
    }

    private void cancel(AttemptRef ref) {
        coordinator.send(Envelope.builder(MessageType.CANCEL).workerId("test-worker").attempt(ref)
                .payload(new CancelPayload(CancelReason.USER_REQUESTED, null)));
    }

    private static ErrorPayload error(Envelope message) {
        assertEquals(MessageType.ERROR, message.type(), message.toString());
        return message.payload(ErrorPayload.class);
    }

    private static void assertRejected(Envelope message, boolean retryable, String reason) {
        ErrorPayload error = error(message);
        assertEquals(ProtocolErrorCode.TASK_REJECTED, error.code());
        assertEquals(retryable, error.retryable(), error.message());
        assertTrue(error.message().contains(reason), error.message());
        assertNotNull(message.attemptId());
    }
}
