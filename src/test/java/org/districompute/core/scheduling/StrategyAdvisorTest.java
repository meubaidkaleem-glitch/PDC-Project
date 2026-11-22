package org.districompute.core.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.execution.EngineConfig;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.fixtures.SumOfSquaresAlgorithm;
import org.districompute.core.metrics.BenchmarkReport;
import org.districompute.core.metrics.BenchmarkRunner;
import org.districompute.core.metrics.BenchmarkSuite;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.GpuStatus;
import org.districompute.core.resource.ResourceSnapshot;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.scheduling.StrategyAssessment.Verdict;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.validation.ParameterValidator;
import org.junit.jupiter.api.Test;

class StrategyAdvisorTest {

    private static final AlgorithmRegistry REGISTRY = AlgorithmRegistry.loadDefault();
    private static final StrategyAdvisor ADVISOR = new StrategyAdvisor(StrategyAdvisor.Settings.defaults());
    private static final long GIB = 1L << 30;

    private static ResourceSnapshot resources(int cores, long memory, List<ResourceSnapshot.Node> workers,
            ResourceSnapshot.Network network) {
        return new ResourceSnapshot(SchemaVersion.CURRENT, Instant.now(), new ResourceSnapshot.Node("local", cores,
                memory), workers, network, List.of());
    }

    private static StrategyRecommendation recommend(String algorithm, Map<String, Object> parameters,
            ResourceSnapshot resources, List<Calibration> history) {
        ComputeAlgorithm<?, ?> impl = REGISTRY.find(AlgorithmId.of(algorithm)).orElseThrow();
        WorkloadProfile profile = impl.analyze(ParameterValidator.validate(impl.descriptor().parameters(),
                ParameterValues.normalizeAll(parameters)));
        return ADVISOR.recommend(profile, REGISTRY.info(AlgorithmId.of(algorithm)).orElseThrow().capabilities(),
                resources, history, GpuStatus.none());
    }

    private static Verdict verdict(StrategyRecommendation recommendation, ExecutionMode mode) {
        return recommendation.assessment(mode).orElseThrow().verdict();
    }

    @Test
    void smallWorkloadsStaySequential() {
        StrategyRecommendation r = recommend("matrix.multiply", Map.of("rowsA", 64, "colsA", 64, "colsB", 64),
                resources(8, 4 * GIB, List.of(), null), List.of());
        assertEquals(ExecutionMode.SEQUENTIAL_CPU, r.recommended());
        assertEquals(Verdict.UNSUITABLE, verdict(r, ExecutionMode.MULTITHREADED_CPU));
        assertTrue(r.assessment(ExecutionMode.MULTITHREADED_CPU).orElseThrow().reasons().get(0)
                .contains("too small"));
        assertFalse(r.calibrated());
    }

    @Test
    void largeComputeBoundWorkloadsGoParallelAndUnavailableModesAreExplained() {
        StrategyRecommendation r = recommend("matrix.multiply", Map.of("rowsA", 1024, "colsA", 1024, "colsB", 1024),
                resources(4, 4 * GIB, List.of(), null), List.of());
        assertEquals(ExecutionMode.MULTITHREADED_CPU, r.recommended());
        assertEquals(Verdict.RECOMMENDED, verdict(r, ExecutionMode.MULTITHREADED_CPU));
        assertEquals(Verdict.UNSUITABLE, verdict(r, ExecutionMode.DISTRIBUTED_CPU), "no workers supplied");
        assertEquals(Verdict.UNSUPPORTED, verdict(r, ExecutionMode.GPU));
        assertTrue(r.assessment(ExecutionMode.GPU).orElseThrow().reasons().get(0).contains("No GPU backend"));
        assertEquals(Verdict.UNSUPPORTED, verdict(r, ExecutionMode.HYBRID));
    }

    @Test
    void calibrationFromMeasuredBenchmarksDrivesEstimates() {
        BenchmarkReport report;
        EngineConfig config = EngineConfig.defaults().withMaxThreads(2);
        try (LocalExecutionEngine engine = new LocalExecutionEngine(REGISTRY, config)) {
            report = new BenchmarkRunner(engine).run(new BenchmarkSuite(SchemaVersion.CURRENT, "calibration", "", 1,
                    2, "matrix.multiply", List.of(Map.of("rowsA", 96, "colsA", 96, "colsB", 96)), List.of(2),
                    List.of(PartitioningOptions.defaults()), true), line -> { });
        }
        List<Calibration> history = Calibration.fromReport(report);
        assertEquals(2, history.size());
        StrategyRecommendation r = recommend("matrix.multiply", Map.of("rowsA", 800, "colsA", 800, "colsB", 800),
                resources(2, 4 * GIB, List.of(), null), history);
        assertTrue(r.calibrated());
        assertNotNull(r.assessment(ExecutionMode.SEQUENTIAL_CPU).orElseThrow().estimatedSeconds());
        assertNotNull(r.assessment(ExecutionMode.MULTITHREADED_CPU).orElseThrow().estimatedSeconds());
        assertNotNull(r.recommended());
    }

    @Test
    void synthesizedCalibrationPicksTheFastestSuitableMode() {
        AlgorithmId id = AlgorithmId.of("primes.count");
        List<Calibration> fastParallel = List.of(new Calibration(id, ExecutionMode.SEQUENTIAL_CPU, 1, 1e9, 1e9),
                new Calibration(id, ExecutionMode.MULTITHREADED_CPU, 4, 3.4e9, 1e9));
        StrategyRecommendation r = recommend("primes.count", Map.of("upperBound", 10_000_000_000L),
                resources(4, 4 * GIB, List.of(), null), fastParallel);
        assertEquals(ExecutionMode.MULTITHREADED_CPU, r.recommended());

        List<Calibration> slowParallel = List.of(new Calibration(id, ExecutionMode.SEQUENTIAL_CPU, 1, 1e9, 1e9),
                new Calibration(id, ExecutionMode.MULTITHREADED_CPU, 4, 0.5e9, 1e9));
        assertEquals(ExecutionMode.SEQUENTIAL_CPU, recommend("primes.count", Map.of("upperBound", 10_000_000_000L),
                resources(4, 4 * GIB, List.of(), null), slowParallel).recommended(),
                "measured history overrides the assumption that parallel is faster");
    }

    @Test
    void workloadsTooLargeForThisNodeAreSentToEnoughWorkers() {
        Map<String, Object> huge = Map.of("rowsA", 20_000, "colsA", 20_000, "colsB", 20_000);
        List<ResourceSnapshot.Node> workers = List.of(new ResourceSnapshot.Node("w1", 8, 16 * GIB),
                new ResourceSnapshot.Node("w2", 8, 16 * GIB));
        StrategyRecommendation r = recommend("matrix.multiply", huge, resources(4, 2 * GIB, workers,
                new ResourceSnapshot.Network(1e9, 0.5)), List.of());
        assertEquals(Verdict.UNSUITABLE, verdict(r, ExecutionMode.SEQUENTIAL_CPU));
        assertEquals(Verdict.UNSUITABLE, verdict(r, ExecutionMode.MULTITHREADED_CPU));
        assertEquals(ExecutionMode.DISTRIBUTED_CPU, r.recommended());

        StrategyRecommendation nowhere = recommend("matrix.multiply", huge, resources(4, 2 * GIB, List.of(), null),
                List.of());
        assertEquals(null, nowhere.recommended(), "no mode is suitable, and the advisor says so");
    }

    @Test
    void communicationBoundWorkIsNotDistributed() {
        AlgorithmId id = AlgorithmId.of("matrix.add");
        List<Calibration> history = List.of(new Calibration(id, ExecutionMode.SEQUENTIAL_CPU, 1, 1e9, 1e6));
        List<ResourceSnapshot.Node> workers = List.of(new ResourceSnapshot.Node("w1", 4, 8 * GIB));
        StrategyRecommendation r = recommend("matrix.add", Map.of("rows", 4000, "cols", 4000),
                resources(4, 4 * GIB, workers, new ResourceSnapshot.Network(1.25e8, 1.0)), history);
        StrategyAssessment distributed = r.assessment(ExecutionMode.DISTRIBUTED_CPU).orElseThrow();
        assertEquals(Verdict.UNSUITABLE, distributed.verdict());
        assertTrue(distributed.reasons().stream().anyMatch(reason -> reason.contains("communication")),
                distributed.reasons().toString());
    }

    @Test
    void nonPartitionableAlgorithmsOnlySupportSequentialExecution() {
        SumOfSquaresAlgorithm fixture = new SumOfSquaresAlgorithm();
        AlgorithmRegistry registry = AlgorithmRegistry.of(fixture);
        WorkloadProfile profile = fixture.analyze(ParameterValidator.validate(fixture.descriptor().parameters(),
                Map.of("n", 1_000_000)));
        StrategyRecommendation r = ADVISOR.recommend(profile, registry.info(fixture.descriptor().id()).orElseThrow()
                .capabilities(), resources(8, 4 * GIB, List.of(), null), List.of(), GpuStatus.none());
        assertEquals(ExecutionMode.SEQUENTIAL_CPU, r.recommended());
        assertEquals(Verdict.UNSUPPORTED, verdict(r, ExecutionMode.MULTITHREADED_CPU));
        assertEquals(Verdict.UNSUPPORTED, verdict(r, ExecutionMode.DISTRIBUTED_CPU));
    }
}
