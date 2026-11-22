package org.districompute.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.districompute.core.execution.EngineConfig;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.ExecutionOutcome;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.Test;

/**
 * Conformance test over the real catalog: every built-in algorithm must complete, with its default parameters,
 * in every execution mode it advertises that the local engine can run. A mode that cannot be demonstrated fails
 * this test.
 */
class BuiltInCatalogTest {

    @Test
    void discoversTheBuiltInAlgorithms() {
        List<String> ids = AlgorithmRegistry.loadDefault().catalog().stream()
                .map(info -> info.descriptor().id().value()).toList();
        assertEquals(List.of("data.filter", "data.statistics", "matrix.add", "matrix.multiply", "numeric.integrate",
                "numeric.monte-carlo", "numeric.polynomial-eval", "numeric.root-find", "primes.count", "sort.merge"),
                ids);
    }

    @Test
    void everyAdvertisedLocalModeCompletesWithDefaultParameters() {
        try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(),
                EngineConfig.defaults())) {
            for (AlgorithmInfo info : engine.registry().catalog()) {
                for (ExecutionMode mode : info.capabilities().supportedModes()) {
                    if (!mode.isLocal()) {
                        continue;
                    }
                    ExecutionOutcome outcome = engine.execute(
                            TaskSpec.builder(info.descriptor().id().value()).mode(mode).build());
                    assertEquals(TaskStatus.COMPLETED, outcome.status(),
                            () -> info.descriptor().id() + " in " + mode + ": " + outcome.taskResult().error());
                    assertTrue(outcome.taskResult().verification().passed());
                }
            }
        }
    }
}
