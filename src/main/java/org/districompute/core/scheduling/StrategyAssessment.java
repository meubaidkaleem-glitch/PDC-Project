package org.districompute.core.scheduling;

import java.util.List;
import java.util.Objects;
import org.districompute.core.execution.ExecutionMode;

/**
 * The advisor's verdict on one execution mode.
 *
 * @param mode execution mode
 * @param verdict verdict
 * @param threads threads (local modes) or total worker cores (distributed) the estimate assumes, or {@code null}
 * @param estimatedSeconds estimated execution time, or {@code null} when it cannot be estimated
 * @param reasons why the verdict was reached, in plain words
 */
public record StrategyAssessment(ExecutionMode mode, Verdict verdict, Integer threads, Double estimatedSeconds,
        List<String> reasons) {

    /** Verdict on a mode. */
    public enum Verdict {
        /** The best suitable mode. */
        RECOMMENDED,
        /** Implemented and expected to work, but not the best choice. */
        SUITABLE,
        /** Implemented, but inefficient or infeasible for this workload or these resources. */
        UNSUITABLE,
        /** No working implementation (or required runtime) exists. */
        UNSUPPORTED
    }

    /** Validates the components. */
    public StrategyAssessment {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(verdict, "verdict");
        reasons = List.copyOf(reasons);
    }

    /**
     * Copy with a different verdict.
     *
     * @param newVerdict verdict
     * @return the copy
     */
    public StrategyAssessment withVerdict(Verdict newVerdict) {
        return new StrategyAssessment(mode, newVerdict, threads, estimatedSeconds, reasons);
    }
}
