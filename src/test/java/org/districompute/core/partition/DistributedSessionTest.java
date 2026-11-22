package org.districompute.core.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.result.PartialResultCollector.Classification;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Simulates the distributed runtime in-process: a coordinator session and several workers that exchange only JSON
 * strings, the way Contributor 2's transport would. Results must equal the local sequential baseline.
 */
@Tag("concurrency")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class DistributedSessionTest {

    private static final AlgorithmRegistry REGISTRY = AlgorithmRegistry.loadDefault();

    private static String sequentialDigest(String algorithm, Map<String, Object> parameters) {
        try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
            return engine.execute(TaskSpec.builder(algorithm).parameters(parameters).build()).taskResult().result()
                    .digest();
        }
    }

    private static TaskSpec distributed(String algorithm, Map<String, Object> parameters) {
        return TaskSpec.builder(algorithm).parameters(parameters).mode(ExecutionMode.DISTRIBUTED_CPU).build();
    }

    /** One worker round trip, entirely through JSON. */
    private static String workerRoundTrip(PartitionWorker worker, String workUnitJson) {
        WorkUnitMessage message = ContractJson.fromJson(workUnitJson, WorkUnitMessage.class);
        return ContractJson.toJson(worker.execute(message, CancellationToken.NONE, ProgressReporter.NONE));
    }

    @Test
    void workersReproduceTheSequentialResultForEveryAlgorithm() throws Exception {
        List<Map.Entry<String, Map<String, Object>>> cases = List.of(
                Map.entry("matrix.multiply", Map.of("rowsA", 70, "colsA", 50, "colsB", 60)),
                Map.entry("matrix.add", Map.of("rows", 90, "cols", 40)),
                Map.entry("primes.count", Map.of("upperBound", 3_000_000)),
                Map.entry("sort.merge", Map.of("size", 100_000)));
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            for (Map.Entry<String, Map<String, Object>> testCase : cases) {
                PartitionedTaskSession session = PartitionedTaskSession.open(REGISTRY,
                        distributed(testCase.getKey(), testCase.getValue()), 6);
                assertEquals(TaskStatus.QUEUED, session.status());
                List<Future<String>> replies = new ArrayList<>();
                for (PartitionDescriptor descriptor : session.partitions()) {
                    String request = ContractJson.toJson(session.workUnit(descriptor.index(), 1));
                    PartitionWorker worker = new PartitionWorker(REGISTRY, "worker-" + descriptor.index() % 3);
                    replies.add(workers.submit(() -> workerRoundTrip(worker, request)));
                }
                for (Future<String> reply : replies) {
                    PartialResultMessage message = ContractJson.fromJson(reply.get(60, TimeUnit.SECONDS),
                            PartialResultMessage.class);
                    assertEquals(Classification.ACCEPTED, session.accept(message).classification());
                }
                TaskResult result = session.complete();
                assertEquals(TaskStatus.COMPLETED, result.status(), () -> String.valueOf(result.error()));
                assertEquals(ExecutionMode.DISTRIBUTED_CPU, result.mode());
                assertEquals(sequentialDigest(testCase.getKey(), testCase.getValue()), result.result().digest(),
                        testCase.getKey());
                assertTrue(result.metrics().busyNanos() > 0);
                assertEquals(result, ContractJson.fromJson(ContractJson.toJson(result), TaskResult.class));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void recoversFromALostWorkerAndClassifiesBadMessages() {
        PartitionedTaskSession session = PartitionedTaskSession.open(REGISTRY,
                distributed("matrix.multiply", Map.of("rowsA", 40, "colsA", 30, "colsB", 20)), 4);
        PartitionWorker worker = new PartitionWorker(REGISTRY, "worker-a");
        List<PartialResultMessage> results = new ArrayList<>();
        for (PartitionDescriptor descriptor : session.partitions()) {
            results.add(worker.execute(session.workUnit(descriptor.index(), 1), CancellationToken.NONE,
                    ProgressReporter.NONE));
        }

        // Partition 2's worker "dies": its result never arrives; the coordinator records the failure.
        for (int i = 0; i < results.size(); i++) {
            if (i != 2) {
                assertEquals(Classification.ACCEPTED, session.accept(results.get(i)).classification());
            }
        }
        session.markFailed(2, ErrorInfo.of(ErrorCode.PARTITION_FAILED, "worker-b stopped responding"));
        assertEquals(List.of(2), session.missing());
        assertEquals(PartitionStatus.FAILED, session.partitionStatus(2));
        assertTrue(session.retryEligible(2));
        assertFalse(session.retryEligible(0), "completed partitions are not re-run");
        DistriComputeException premature = assertThrows(DistriComputeException.class, session::complete);
        assertEquals(ErrorCode.MISSING_PARTIAL_RESULT, premature.code());
        assertEquals(TaskStatus.RUNNING, session.status(), "a premature complete() leaves the session usable");

        // Bad messages are classified, never silently dropped or aggregated.
        assertEquals(Classification.DUPLICATE, session.accept(results.get(0)).classification());
        PartialResultMessage corrupted = withPayload(results.get(2), corrupt(results.get(2).payload()));
        assertEquals(Classification.INVALID, session.accept(corrupted).classification());
        PartialResultMessage misplaced = new PartialResultMessage(results.get(1).schemaVersion(),
                results.get(1).taskId(), PartitionDescriptor.idOf(results.get(1).taskId(), 2), 2, 1,
                results.get(1).resultType(), results.get(1).payload(), 1, "worker-a");
        assertEquals(Classification.INVALID, session.accept(misplaced).classification(),
                "a block for partition 1 cannot fill partition 2");
        PartialResultMessage foreign = new PartialResultMessage(results.get(2).schemaVersion(), "other-task",
                results.get(2).partitionId(), 2, 1, results.get(2).resultType(), results.get(2).payload(), 1, "x");
        assertEquals(Classification.INVALID, session.accept(foreign).classification());
        assertEquals(List.of(2), session.missing());

        // Eligible recovery: re-dispatch partition 2 (attempt 2) to another worker.
        PartialResultMessage retried = new PartitionWorker(REGISTRY, "worker-c").execute(session.workUnit(2, 2),
                CancellationToken.NONE, ProgressReporter.NONE);
        assertEquals(2, retried.attempt());
        assertEquals(Classification.ACCEPTED, session.accept(retried).classification());
        TaskResult result = session.complete();
        assertEquals(TaskStatus.COMPLETED, result.status());
        assertEquals(sequentialDigest("matrix.multiply", Map.of("rowsA", 40, "colsA", 30, "colsB", 20)),
                result.result().digest());
        assertEquals(Classification.LATE, session.accept(results.get(1)).classification());
    }

    @Test
    void rejectsTasksThatAreNotDistributedOrInvalid() {
        DistriComputeException mode = assertThrows(DistriComputeException.class, () -> PartitionedTaskSession.open(
                REGISTRY, TaskSpec.builder("sort.merge").build(), 4));
        assertEquals(ErrorCode.UNSUPPORTED_EXECUTION_MODE, mode.code());
        DistriComputeException invalid = assertThrows(DistriComputeException.class, () -> PartitionedTaskSession
                .open(REGISTRY, distributed("matrix.multiply", Map.of("rowsA", -1)), 4));
        assertEquals(ErrorCode.VALIDATION_FAILED, invalid.code());
    }

    @Test
    void cancellationEndsTheSession() {
        PartitionedTaskSession session = PartitionedTaskSession.open(REGISTRY,
                distributed("primes.count", Map.of("upperBound", 1_000_000)), 2);
        TaskResult cancelled = session.cancel(CancellationReason.USER_REQUESTED);
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertEquals(ErrorCode.CANCELLED, cancelled.error().code());
        assertThrows(IllegalStateException.class, () -> session.workUnit(0, 1));
    }

    private static EncodedPayload corrupt(EncodedPayload payload) {
        byte[] bytes = payload.decodeBytes();
        bytes[bytes.length / 2] ^= 0x01;
        return EncodedPayload.of(payload.format(), bytes);
    }

    private static PartialResultMessage withPayload(PartialResultMessage message, EncodedPayload payload) {
        return new PartialResultMessage(message.schemaVersion(), message.taskId(), message.partitionId(),
                message.index(), message.attempt(), message.resultType(), payload, message.computeNanos(),
                message.workerId());
    }
}
