package org.districompute.distributed.strategy;

import java.time.Instant;
import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.resource.ResourceSnapshot;
import org.districompute.core.scheduling.StrategyRecommendation;

/**
 * An execution plan proposal for one task: Core's assessment of every mode, computed from the resources the
 * runtime supplied, plus the runtime's own context. A recommendation is not a promise of the best mode in every
 * environment: Core's estimates rest on the assumptions it lists, and the runtime's measurements describe the
 * cluster at {@link #createdAt()} only.
 *
 * @param createdAt when the advice was computed
 * @param algorithm the resolved algorithm and version
 * @param recommended the mode Core recommends, or {@code null} if none is suitable
 * @param explanation a one-paragraph summary for the user
 * @param assessment Core's full assessment: verdict, estimate and reasons for every mode, and its assumptions
 * @param resources the resource snapshot the runtime gave Core
 * @param runtime the runtime's context (eligible workers, load, network, executable modes)
 */
public record ExecutionAdvice(Instant createdAt, AlgorithmRef algorithm, ExecutionMode recommended,
        String explanation, StrategyRecommendation assessment, ResourceSnapshot resources, RuntimeContext runtime) {

    /** Validates the components. */
    public ExecutionAdvice {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(runtime, "runtime");
    }
}
