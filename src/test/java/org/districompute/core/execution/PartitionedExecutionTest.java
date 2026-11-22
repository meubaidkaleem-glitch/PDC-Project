package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.primes.PrimeCount;
import org.districompute.core.algorithm.primes.PrimeCountingAlgorithm;
import org.districompute.core.algorithm.primes.PrimeRange;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.registry.BuiltInAlgorithms;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Parallel correctness: multithreaded (partitioned) execution must produce bitwise-identical results to the
 * sequential baseline, for every thread count and partition configuration.
 */
@Tag("concurrency")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PartitionedExecutionTest {

    private static LocalExecutionEngine engine;

    @BeforeAll
    static void start() {
        List<ComputeAlgorithm<?, ?>> algorithms = new ArrayList<>(new BuiltInAlgorithms().algorithms());
        algorithms.add(new FailingPartitionAlgorithm());
        algorithms.add(new MisplacedResultAlgorithm());
        engine = new LocalExecutionEngine(AlgorithmRegistry.of(algorithms), EngineConfig.defaults().withMaxThreads(4));
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static TaskResult run(String algorithm, Map<String, Object> parameters, ExecutionMode mode,
            Integer threads, PartitioningOptions options) {
        TaskSpec.Builder builder = TaskSpec.builder(algorithm).parameters(parameters).mode(mode);
        if (threads != null) {
            builder.maxThreads(threads);
        }
        if (options != null) {
            builder.partitioning(options);
        }
        TaskResult result = engine.execute(builder.build()).taskResult();
        assertEquals(TaskStatus.COMPLETED, result.status(), () -> algorithm + " " + mode + " " + options + ": "
                + result.error());
        return result;
    }

    private static void assertMatchesSequential(String algorithm, Map<String, Object> parameters,
            List<PartitioningOptions> options) {
        String expected = run(algorithm, parameters, ExecutionMode.SEQUENTIAL_CPU, null, null).result().digest();
        for (int threads : new int[] {1, 2, 4}) {
            for (PartitioningOptions option : options) {
                TaskResult parallel = run(algorithm, parameters, ExecutionMode.MULTITHREADED_CPU, threads, option);
                assertEquals(expected, parallel.result().digest(), algorithm + " threads=" + threads + " " + option);
                assertEquals(threads, parallel.threads());
                assertTrue(parallel.metrics().partitions() >= 1);
                assertTrue(parallel.metrics().executionNanos() >= parallel.metrics().computeNanos());
            }
        }
    }

    @Test
    void matrixMultiplicationIsBitwiseIdenticalForEveryDecomposition() {
        assertMatchesSequential("matrix.multiply", Map.of("rowsA", 97, "colsA", 61, "colsB", 83, "blockSize", 16),
                List.of(PartitioningOptions.defaults(), PartitioningOptions.ofPartitions(1),
                        PartitioningOptions.ofPartitions(7), PartitioningOptions.ofBlocks(10, 30),
                        PartitioningOptions.ofBlocks(97, 1), PartitioningOptions.ofBlocks(1, 83)));
    }

    @Test
    void matrixAdditionIsBitwiseIdenticalForEveryDecomposition() {
        assertMatchesSequential("matrix.add", Map.of("rows", 211, "cols", 107),
                List.of(PartitioningOptions.defaults(), PartitioningOptions.ofPartitions(5),
                        PartitioningOptions.ofBlocks(50, 20)));
    }

    @Test
    void primeCountingMatchesForEveryRangeSplit() {
        assertMatchesSequential("primes.count", Map.of("lowerBound", 1_000_003, "upperBound", 9_000_017),
                List.of(PartitioningOptions.defaults(), PartitioningOptions.ofPartitions(13),
                        PartitioningOptions.ofPartitionSize(777_777)));
    }

    @Test
    void mergeSortMatchesForEveryChunking() {
        for (String distribution : List.of("UNIFORM", "FEW_UNIQUE", "REVERSED")) {
            assertMatchesSequential("sort.merge", Map.of("size", 200_003, "distribution", distribution),
                    List.of(PartitioningOptions.defaults(), PartitioningOptions.ofPartitions(9),
                            PartitioningOptions.ofPartitionSize(50_000)));
        }
        assertMatchesSequential("sort.merge", Map.of("size", 0), List.of(PartitioningOptions.ofPartitions(4)));
    }

    @Test
    void reportsPartitionAndOverheadMetrics() {
        TaskResult result = run("matrix.multiply", Map.of("rowsA", 128, "colsA", 128, "colsB", 128),
                ExecutionMode.MULTITHREADED_CPU, 4, PartitioningOptions.ofPartitions(8));
        assertEquals(8, result.partitions());
        assertEquals(8, result.metrics().partitions());
        assertTrue(result.metrics().busyNanos() > 0);
        assertTrue(result.metrics().schedulingOverheadNanos() >= 0);
        assertTrue(result.metrics().partitioningNanos() >= 0 && result.metrics().aggregationNanos() >= 0);
    }

    @Test
    void rejectsThreadCountsAboveTheEnginePoolAndBadPartitioningOptions() {
        TaskResult tooMany = engine.execute(TaskSpec.builder("primes.count").mode(ExecutionMode.MULTITHREADED_CPU)
                .maxThreads(64).build()).taskResult();
        assertEquals(ErrorCode.VALIDATION_FAILED, tooMany.error().code());
        assertEquals("constraints.maxThreads", tooMany.error().violations().get(0).field());
        assertEquals(TaskStatus.VALIDATING, tooMany.error().status());

        TaskResult badOptions = engine.execute(TaskSpec.builder("primes.count").mode(ExecutionMode.MULTITHREADED_CPU)
                .partitioning(PartitioningOptions.ofBlocks(2, 2)).build()).taskResult();
        assertEquals(ErrorCode.VALIDATION_FAILED, badOptions.error().code());
    }

    @Test
    void failingPartitionFailsTheTaskAndNamesThePartition() {
        TaskResult result = engine.execute(TaskSpec.builder(FailingPartitionAlgorithm.ID)
                .parameter("upperBound", 5_000_000).mode(ExecutionMode.MULTITHREADED_CPU)
                .partitioning(PartitioningOptions.ofPartitions(6)).build()).taskResult();
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals(ErrorCode.PARTITION_FAILED, result.error().code());
        assertEquals(3, result.error().partitionIndex());
        assertTrue(result.error().retryable());
    }

    @Test
    void invalidPartialResultIsNeverAggregated() {
        TaskResult result = engine.execute(TaskSpec.builder(MisplacedResultAlgorithm.ID)
                .parameter("upperBound", 5_000_000).mode(ExecutionMode.MULTITHREADED_CPU)
                .partitioning(PartitioningOptions.ofPartitions(4)).build()).taskResult();
        assertEquals(ErrorCode.INVALID_PARTIAL_RESULT, result.error().code());
        assertTrue(result.error().message().contains("does not match") || result.error().message().contains("is for"),
                result.error().message());
    }

    /** Prime counting whose partition 3 throws. */
    static final class FailingPartitionAlgorithm extends PrimeCountingAlgorithm {
        static final String ID = "test.failing-partition";
        private final org.districompute.core.algorithm.AlgorithmDescriptor descriptor =
                renamed(super.descriptor(), ID);

        @Override
        public org.districompute.core.algorithm.AlgorithmDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public PrimeCount computePartition(PrimeRange work, ExecutionContext context) {
            if (work.lower() > 2_000_000 && work.lower() < 3_000_000) {
                throw new IllegalStateException("simulated kernel crash");
            }
            return super.computePartition(work, context);
        }
    }

    /** Prime counting that reports its count for the wrong sub-range. */
    static final class MisplacedResultAlgorithm extends PrimeCountingAlgorithm {
        static final String ID = "test.misplaced-result";
        private final org.districompute.core.algorithm.AlgorithmDescriptor descriptor =
                renamed(super.descriptor(), ID);

        @Override
        public org.districompute.core.algorithm.AlgorithmDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public PrimeCount computePartition(PrimeRange work, ExecutionContext context) {
            PrimeCount correct = super.computePartition(work, context);
            return new PrimeCount(correct.lower() + 1, correct.upper(), correct.count());
        }
    }

    static org.districompute.core.algorithm.AlgorithmDescriptor renamed(
            org.districompute.core.algorithm.AlgorithmDescriptor original, String id) {
        return new org.districompute.core.algorithm.AlgorithmDescriptor(
                org.districompute.core.algorithm.AlgorithmId.of(id), original.version(), original.displayName(),
                original.description(), original.category(), original.parameters(), original.resultType(),
                original.verificationMethod(), original.limitations());
    }
}
