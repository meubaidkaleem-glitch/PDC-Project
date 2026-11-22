package org.districompute.core.scheduling;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;

/**
 * Outcome of strategy assessment: one verdict per execution mode and the recommended mode, if any.
 *
 * @param algorithm assessed algorithm
 * @param recommended recommended mode, or {@code null} if no mode is suitable
 * @param assessments one assessment per {@link ExecutionMode}
 * @param calibrated whether measured throughput (rather than defaults) drove the estimates
 * @param assumptions the defaults and simplifications the estimates rely on
 */
public record StrategyRecommendation(AlgorithmRef algorithm, ExecutionMode recommended,
        List<StrategyAssessment> assessments, boolean calibrated, List<String> assumptions) {

    /** Validates the components. */
    public StrategyRecommendation {
        Objects.requireNonNull(algorithm, "algorithm");
        assessments = List.copyOf(assessments);
        assumptions = List.copyOf(assumptions);
    }

    /**
     * Returns the assessment of a mode.
     *
     * @param mode execution mode
     * @return the assessment
     */
    public Optional<StrategyAssessment> assessment(ExecutionMode mode) {
        return assessments.stream().filter(a -> a.mode() == mode).findFirst();
    }
}
