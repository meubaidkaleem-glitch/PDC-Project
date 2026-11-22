package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.matrix.Matrix;
import org.districompute.core.execution.LocalExecutionEngineTest.Fixtures.FailingAlgorithm;
import org.districompute.core.execution.LocalExecutionEngineTest.Fixtures.WrongResultAlgorithm;
import org.districompute.core.fixtures.SumOfSquaresAlgorithm;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.registry.BuiltInAlgorithms;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.ContractJson;
import org.districompute.core.task.StatusChange;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalExecutionEngineTest {

    private static final List<TaskStatus> FULL_PATH = List.of(TaskStatus.CREATED, TaskStatus.VALIDATING,
            TaskStatus.VALIDATED, TaskStatus.PLANNED, TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.AGGREGATING,
            TaskStatus.VERIFYING, TaskStatus.COMPLETED);

    private LocalExecutionEngine engine;

    @BeforeEach
    void createEngine() {
        List<ComputeAlgorithm<?, ?>> algorithms = new ArrayList<>(new BuiltInAlgorithms().algorithms());
        algorithms.addAll(List.of(new SumOfSquaresAlgorithm(), new WrongResultAlgorithm(), new FailingAlgorithm()));
        engine = new LocalExecutionEngine(AlgorithmRegistry.of(algorithms), EngineConfig.defaults());
    }

    @AfterEach
    void closeEngine() {
        engine.close();
    }

    private static List<TaskStatus> history(ExecutionOutcome outcome) {
        return outcome.taskResult().statusHistory().stream().map(StatusChange::status).toList();
    }

    @Test
    void completesMatrixMultiplicationThroughTheFullLifecycle() {
        TaskSpec spec = TaskSpec.builder("matrix.multiply").experimentId("exp-1")
                .parameter("rowsA", 64).parameter("colsA", 48).parameter("colsB", 32).parameter("blockSize", 16)
                .build();
        ExecutionOutcome outcome = engine.execute(spec);
        TaskResult result = outcome.taskResult();

        assertEquals(TaskStatus.COMPLETED, result.status(), () -> String.valueOf(result.error()));
        assertEquals(FULL_PATH, history(outcome));
        assertEquals("1.0.0", result.algorithm().version().toString());
        assertTrue(result.verification().passed());
        assertEquals(Map.of("rowsA", 64L, "colsA", 48L, "colsB", 32L), result.profile().dimensions());
        Matrix product = outcome.value(Matrix.class).orElseThrow();
        assertEquals(product.digest(), result.result().digest());
        assertEquals(1, result.threads());
        assertEquals(1, result.partitions());
        assertTrue(result.metrics().executionNanos() >= result.metrics().computeNanos());
        assertTrue(result.metrics().computeNanos() > 0);
        assertNull(result.error());
    }

    @Test
    void taskResultsSurviveAJsonRoundTrip() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder("primes.count")
                .parameter("upperBound", 100_000).build());
        String json = ContractJson.toJson(outcome.taskResult());
        assertEquals(outcome.taskResult(), ContractJson.fromJson(json, TaskResult.class));
        assertEquals(9592L, outcome.taskResult().result().values().get("count"));
    }

    @Test
    void unknownAlgorithmFailsDuringValidation() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder("matrix.invert").build());
        assertEquals(TaskStatus.FAILED, outcome.status());
        assertEquals(ErrorCode.UNKNOWN_ALGORITHM, outcome.taskResult().error().code());
        assertEquals(TaskStatus.VALIDATING, outcome.taskResult().error().status());
        assertEquals(List.of(TaskStatus.CREATED, TaskStatus.VALIDATING, TaskStatus.FAILED), history(outcome));
        assertTrue(outcome.value(Object.class).isEmpty());
    }

    @Test
    void invalidParametersReportEveryViolation() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder("matrix.multiply")
                .parameter("rowsA", 0).parameter("blockSize", 3).parameter("shape", "square").build());
        assertEquals(ErrorCode.VALIDATION_FAILED, outcome.taskResult().error().code());
        List<String> fields = outcome.taskResult().error().violations().stream().map(Violation::field).toList();
        assertTrue(fields.containsAll(List.of("parameters.rowsA", "parameters.blockSize", "parameters.shape")),
                fields.toString());
        assertFalse(outcome.taskResult().error().retryable());
    }

    @Test
    void rejectsModesWithoutAWorkingLocalImplementation() {
        for (ExecutionMode mode : List.of(ExecutionMode.DISTRIBUTED_CPU, ExecutionMode.GPU, ExecutionMode.HYBRID)) {
            ExecutionOutcome outcome = engine.execute(TaskSpec.builder(SumOfSquaresAlgorithm.ID).mode(mode).build());
            assertEquals(ErrorCode.UNSUPPORTED_EXECUTION_MODE, outcome.taskResult().error().code(), mode.name());
        }
        ExecutionOutcome threaded = engine.execute(TaskSpec.builder(SumOfSquaresAlgorithm.ID)
                .mode(ExecutionMode.MULTITHREADED_CPU).build());
        assertEquals(ErrorCode.UNSUPPORTED_EXECUTION_MODE, threaded.taskResult().error().code(),
                "the fixture does not implement partitioning");
    }

    @Test
    void sequentialModeRejectsThreadAndPartitionRequests() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder(SumOfSquaresAlgorithm.ID).maxThreads(4).build());
        assertEquals("constraints.maxThreads", outcome.taskResult().error().violations().get(0).field());
    }

    @Test
    void memoryGuardRejectsOversizedTasksBeforeAllocating() {
        ExecutionOutcome capped = engine.execute(TaskSpec.builder("sort.merge").parameter("size", 10_000_000)
                .maxMemoryBytes(1_000_000).build());
        assertEquals(ErrorCode.RESOURCE_EXHAUSTED, capped.taskResult().error().code());
        assertEquals(TaskStatus.VALIDATING, capped.taskResult().error().status());
        assertNotNull(capped.taskResult().profile(), "the profile explains the rejection");

        long threeMatrices = 3L * 20_000 * 20_000 * 8;
        if (Runtime.getRuntime().maxMemory() < threeMatrices) {
            ExecutionOutcome huge = engine.execute(TaskSpec.builder("matrix.multiply").parameter("rowsA", 20_000)
                    .parameter("colsA", 20_000).parameter("colsB", 20_000).build());
            assertEquals(ErrorCode.RESOURCE_EXHAUSTED, huge.taskResult().error().code());
        }
    }

    @Test
    void deadlineFailsTheTaskWithTimeout() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder("primes.count")
                .parameter("upperBound", 100_000_000_000L).timeoutMillis(100).build());
        assertEquals(TaskStatus.FAILED, outcome.status());
        assertEquals(ErrorCode.TIMEOUT, outcome.taskResult().error().code());
        assertTrue(outcome.taskResult().error().retryable());
    }

    @Test
    void cancellationBeforeStartEndsCancelled() {
        CancellationSource cancellation = new CancellationSource();
        cancellation.cancel(CancellationReason.USER_REQUESTED);
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder(SumOfSquaresAlgorithm.ID).build(), cancellation);
        assertEquals(TaskStatus.CANCELLED, outcome.status());
        assertEquals(ErrorCode.CANCELLED, outcome.taskResult().error().code());
        assertEquals(TaskStatus.QUEUED, outcome.taskResult().error().status());
    }

    @Test
    void failedVerificationNeverCompletes() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder(WrongResultAlgorithm.ID).build());
        TaskResult result = outcome.taskResult();
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals(ErrorCode.VERIFICATION_FAILED, result.error().code());
        assertEquals(TaskStatus.VERIFYING, result.error().status());
        assertFalse(result.verification().passed());
        assertNotNull(result.result(), "the rejected result is still summarized for diagnosis");
        assertTrue(outcome.value(Object.class).isEmpty(), "an unverified value is never exposed");
    }

    @Test
    void unexpectedExceptionsBecomeExecutionFailures() {
        ExecutionOutcome outcome = engine.execute(TaskSpec.builder(FailingAlgorithm.ID).build());
        assertEquals(ErrorCode.EXECUTION_FAILED, outcome.taskResult().error().code());
        assertEquals(TaskStatus.RUNNING, outcome.taskResult().error().status());
        assertTrue(outcome.taskResult().error().message().contains("kernel exploded"));
    }

    @Test
    void closedEngineRejectsNewTasks() {
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.execute(TaskSpec.builder("sort.merge").build()));
    }

    /** Misbehaving algorithms used to exercise the engine's failure handling. */
    static final class Fixtures {
        private Fixtures() {
        }

        /** Returns a wrong sum, so verification must fail. */
        static final class WrongResultAlgorithm extends SumOfSquaresAlgorithm {
            static final String ID = "test.wrong-result";

            WrongResultAlgorithm() {
                super(ID, "1.0.0");
            }

            @Override
            public Long computeSequential(long[] input, ExecutionContext context) {
                return super.computeSequential(input, context) + 1;
            }
        }

        /** Throws from its kernel. */
        static final class FailingAlgorithm extends SumOfSquaresAlgorithm {
            static final String ID = "test.failing";

            FailingAlgorithm() {
                super(ID, "1.0.0");
            }

            @Override
            public Long computeSequential(long[] input, ExecutionContext context) {
                throw new IllegalStateException("kernel exploded");
            }
        }
    }
}
