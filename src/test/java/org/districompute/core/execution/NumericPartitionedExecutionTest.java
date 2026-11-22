package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Parallel correctness of the numerical and data operations. Exact operations must be bitwise identical to
 * sequential execution. Floating-point reductions must pass verification and agree within rounding.
 */
@Tag("concurrency")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class NumericPartitionedExecutionTest {

    private static LocalExecutionEngine engine;

    @BeforeAll
    static void start() {
        engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(), EngineConfig.defaults().withMaxThreads(4));
    }

    @AfterAll
    static void stop() {
        engine.close();
    }

    private static TaskResult run(String algorithm, Map<String, Object> parameters, ExecutionMode mode,
            PartitioningOptions options) {
        TaskSpec.Builder builder = TaskSpec.builder(algorithm).parameters(parameters).mode(mode);
        if (mode == ExecutionMode.MULTITHREADED_CPU) {
            builder.maxThreads(4).partitioning(options);
        }
        TaskResult result = engine.execute(builder.build()).taskResult();
        assertEquals(TaskStatus.COMPLETED, result.status(), () -> algorithm + " " + options + ": " + result.error());
        return result;
    }

    private static void assertBitwiseEqual(String algorithm, Map<String, Object> parameters) {
        String sequential = run(algorithm, parameters, ExecutionMode.SEQUENTIAL_CPU, null).result().digest();
        for (PartitioningOptions options : new PartitioningOptions[] {PartitioningOptions.defaults(),
            PartitioningOptions.ofPartitions(3), PartitioningOptions.ofPartitions(17)}) {
            assertEquals(sequential, run(algorithm, parameters, ExecutionMode.MULTITHREADED_CPU, options).result()
                    .digest(), algorithm + " " + options);
        }
    }

    @Test
    void exactOperationsAreBitwiseIdenticalAcrossPartitionings() {
        assertBitwiseEqual("numeric.polynomial-eval", Map.of("points", 300_001));
        assertBitwiseEqual("numeric.root-find", Map.of("coefficients", java.util.List.of(-6.0, 11.0, -6.0, 1.0),
                "lowerLimit", 0.0, "upperLimit", 4.0, "subintervals", 99_999));
        assertBitwiseEqual("numeric.monte-carlo", Map.of("samples", 3_000_000));
        assertBitwiseEqual("data.filter", Map.of("size", 300_000, "limit", 50));
    }

    @Test
    void floatingPointReductionsAgreeWithinRounding() {
        for (String algorithm : new String[] {"numeric.integrate", "data.statistics"}) {
            Map<String, Object> parameters = algorithm.equals("numeric.integrate")
                    ? Map.of("function", "EXP", "lowerLimit", -1.0, "upperLimit", 1.0, "panels", 2_000_000)
                    : Map.of("size", 1_000_003);
            String key = algorithm.equals("numeric.integrate") ? "value" : "mean";
            double sequential = ((Number) run(algorithm, parameters, ExecutionMode.SEQUENTIAL_CPU, null).result()
                    .values().get(key)).doubleValue();
            double parallel = ((Number) run(algorithm, parameters, ExecutionMode.MULTITHREADED_CPU,
                    PartitioningOptions.ofPartitions(13)).result().values().get(key)).doubleValue();
            assertEquals(sequential, parallel, 1e-12 * Math.max(1, Math.abs(sequential)), algorithm);
        }
    }
}
