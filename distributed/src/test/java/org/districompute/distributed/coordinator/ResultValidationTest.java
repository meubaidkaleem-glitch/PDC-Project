package org.districompute.distributed.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.partition.PartialResultMessage;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.Envelope;
import org.districompute.distributed.protocol.ErrorPayload;
import org.districompute.distributed.protocol.MessageType;
import org.districompute.distributed.protocol.ProtocolErrorCode;
import org.districompute.distributed.protocol.ResultPayload;
import org.districompute.distributed.protocol.TaskPayload;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.registry.WorkerSnapshot;
import org.districompute.distributed.task.AttemptSnapshot;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.SubmitOptions;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.testing.CoreFixtures;
import org.districompute.distributed.testing.ProtocolClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A scripted worker (real socket, real codec, real Core results) misbehaves on purpose: duplicate, corrupted,
 * foreign and unknown results, and reported failures. Only valid results may ever be aggregated.
 */
@Tag("integration")
class ResultValidationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private Coordinator coordinator;

    @AfterEach
    void stop() {
        if (coordinator != null) {
            coordinator.close();
        }
    }

    @Test
    void duplicateResultsAreCountedButAggregatedOnce() throws Exception {
        start();
        try (ProtocolClient worker = scriptedWorker("dup")) {
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("dup-task", 1_000_000, 2));
            // Partition 0: the result twice while the task still runs, so Core sees a DUPLICATE.
            Envelope first = resultFor(worker.receive(MessageType.TASK, WAIT), "dup");
            worker.send(first);
            worker.send(copyWithNewId(first));
            // Partition 1: completes the task; a copy sent afterwards is LATE and changes nothing.
            Envelope second = resultFor(worker.receive(MessageType.TASK, WAIT), "dup");
            worker.send(second);
            TaskOutcome outcome = handle.await(WAIT);
            worker.send(copyWithNewId(second));
            assertEquals(TaskStatus.COMPLETED, outcome.result().status());
            assertTrue(outcome.result().verification().passed());
            assertEquals(2, outcome.snapshot().attempts());
            WorkerSnapshot snapshot = coordinator.worker("dup").orElseThrow();
            assertEquals(2, snapshot.counters().succeeded());
            assertEquals(1, snapshot.counters().duplicateResults());
            assertEquals(0, snapshot.counters().invalidResults(), "duplicates and late copies are not invalid");
        }
    }

    @Test
    void aCorruptedResultIsRejectedAndThePartitionRetried() throws Exception {
        start();
        try (ProtocolClient worker = scriptedWorker("corrupt")) {
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("corrupt-task", 1_000_000, 1));
            Envelope first = worker.receive(MessageType.TASK, WAIT);
            worker.send(corrupted(resultFor(first, "corrupt")));
            Envelope second = worker.receive(MessageType.TASK, WAIT);
            assertEquals("corrupt-task/p0/a2", second.attemptId(), "a new attempt id for the retry");
            worker.send(resultFor(second, "corrupt"));
            TaskOutcome outcome = handle.await(WAIT);
            assertEquals(TaskStatus.COMPLETED, outcome.result().status());
            List<AttemptSnapshot> attempts = outcome.snapshot().partitions().get(0).attempts();
            assertEquals(AttemptStatus.INVALID, attempts.get(0).status());
            assertTrue(attempts.get(0).failure().startsWith("invalid result"), attempts.get(0).failure());
            assertEquals(AttemptStatus.SUCCEEDED, attempts.get(1).status());
            assertEquals(1, outcome.snapshot().retries());
            assertEquals(1, coordinator.worker("corrupt").orElseThrow().counters().invalidResults());
        }
    }

    @Test
    void resultsForAnotherWorkersAttemptOrAnUnknownAttemptAreRefused() throws Exception {
        start();
        try (ProtocolClient a = scriptedWorker("a"); ProtocolClient b = scriptedWorker("b")) {
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("foreign-task", 1_000_000, 1),
                    SubmitOptions.DEFAULTS.withMaxWorkers(1));
            Envelope task = receiveTaskFromEither(a, b);
            ProtocolClient owner = "a".equals(task.workerId()) ? a : b;
            ProtocolClient other = owner == a ? b : a;
            String otherId = owner == a ? "b" : "a";
            other.send(resultFor(task, otherId));
            ErrorPayload refused = other.receive(MessageType.ERROR, WAIT).payload(ErrorPayload.class);
            assertEquals(ProtocolErrorCode.INVALID_MESSAGE, refused.code(), refused.message());

            AttemptRef unknown = new AttemptRef(null, "nobody", "nobody/p0", "nobody/p0/a1");
            PartialResultMessage stray = CoreFixtures.execute(task.payload(TaskPayload.class).workUnit(), otherId);
            other.send(Envelope.builder(MessageType.RESULT).workerId(otherId).attempt(unknown).payload(
                    new ResultPayload(new PartialResultMessage(stray.schemaVersion(), "nobody", "nobody/p0", 0, 1,
                            stray.resultType(), stray.payload(), stray.computeNanos(), otherId),
                            new WorkerTiming(0, 0, 0))));
            assertEquals(ProtocolErrorCode.UNKNOWN_TASK, other.receive(MessageType.ERROR, WAIT)
                    .payload(ErrorPayload.class).code());

            owner.send(resultFor(task, task.workerId()));
            TaskOutcome outcome = handle.await(WAIT);
            assertEquals(TaskStatus.COMPLETED, outcome.result().status());
            assertEquals(1, outcome.snapshot().metrics().workersUsed());
        }
    }

    @Test
    void retryableFailuresAreRetriedUntilTheBudgetIsExhausted() throws Exception {
        start();
        try (ProtocolClient worker = scriptedWorker("flaky")) {
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("flaky-task", 1_000_000, 1),
                    SubmitOptions.DEFAULTS.withMaxAttempts(3));
            for (int attempt = 1; attempt <= 3; attempt++) {
                Envelope task = worker.receive(MessageType.TASK, WAIT);
                assertEquals("flaky-task/p0/a" + attempt, task.attemptId());
                worker.send(failure(task, "flaky", true));
            }
            TaskOutcome outcome = handle.await(WAIT);
            assertEquals(TaskStatus.FAILED, outcome.result().status(), "repeated failures never become success");
            ErrorInfo error = outcome.result().error();
            assertEquals(ErrorCode.PARTITION_FAILED, error.code());
            assertEquals(0, error.partitionIndex());
            assertTrue(error.message().contains("retry budget is exhausted"), error.message());
            assertEquals(3, outcome.snapshot().attempts());
            assertEquals(2, outcome.snapshot().retries());
        }
    }

    @Test
    void aNonRetryableFailureEndsTheTaskImmediately() throws Exception {
        start();
        try (ProtocolClient worker = scriptedWorker("strict")) {
            TaskHandle handle = coordinator.submit(CoreFixtures.primesTask("strict-task", 1_000_000, 1));
            worker.send(failure(worker.receive(MessageType.TASK, WAIT), "strict", false));
            TaskOutcome outcome = handle.await(WAIT);
            assertEquals(TaskStatus.FAILED, outcome.result().status());
            assertTrue(outcome.result().error().message().contains("not retryable"),
                    outcome.result().error().message());
            assertEquals(1, outcome.snapshot().attempts());
        }
    }

    private void start() throws IOException {
        coordinator = Coordinator.start(CoordinatorRegistrationTest.config()
                .heartbeat(Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofSeconds(10)).build());
    }

    private ProtocolClient scriptedWorker(String id) throws IOException {
        ProtocolClient client = ProtocolClient.connect(coordinator.port());
        assertEquals(MessageType.REGISTER_ACK, client.register(id, CoordinatorRegistrationTest.SECRET,
                CoreFixtures.capabilities(1)).type());
        return client;
    }

    private static Envelope receiveTaskFromEither(ProtocolClient a, ProtocolClient b) {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            for (ProtocolClient client : List.of(a, b)) {
                try {
                    return client.receive(MessageType.TASK, Duration.ofMillis(50));
                } catch (AssertionError e) {
                    // Not this one (yet).
                }
            }
        }
        throw new AssertionError("No TASK for either worker");
    }

    private static Envelope resultFor(Envelope task, String workerId) {
        PartialResultMessage partial = CoreFixtures.execute(task.payload(TaskPayload.class).workUnit(), workerId);
        return Envelope.builder(MessageType.RESULT).workerId(workerId).attempt(task.attemptRef())
                .payload(new ResultPayload(partial, new WorkerTiming(1, 2, 3)));
    }

    private static Envelope failure(Envelope task, String workerId, boolean retryable) {
        return Envelope.builder(MessageType.ERROR).workerId(workerId).attempt(task.attemptRef())
                .payload(new ErrorPayload(ProtocolErrorCode.TASK_FAILED, "kernel failed", retryable, false,
                        new ErrorInfo(ErrorCode.PARTITION_FAILED, "kernel failed", retryable, TaskStatus.RUNNING, 0,
                                List.of())));
    }

    private static Envelope copyWithNewId(Envelope message) {
        return Envelope.builder(message.type()).workerId(message.workerId()).attempt(message.attemptRef())
                .payload(message.payload());
    }

    private static Envelope corrupted(Envelope result) {
        ResultPayload payload = result.payload(ResultPayload.class);
        PartialResultMessage p = payload.partialResult();
        EncodedPayload e = p.payload();
        char[] chars = e.base64().toCharArray();
        chars[12] = chars[12] == 'A' ? 'B' : 'A';
        PartialResultMessage tampered = new PartialResultMessage(p.schemaVersion(), p.taskId(), p.partitionId(),
                p.index(), p.attempt(), p.resultType(), new EncodedPayload(e.format(), e.sizeBytes(), e.crc32c(),
                        new String(chars)), p.computeNanos(), p.workerId());
        return Envelope.builder(MessageType.RESULT).workerId(result.workerId()).attempt(result.attemptRef())
                .payload(new ResultPayload(tampered, payload.timing()));
    }
}
