package org.districompute.distributed.strategy;

import java.time.Instant;
import java.util.List;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.scheduling.StrategyAssessment;

/**
 * A recorded manual override: the task was submitted with a different mode than the one recommended at that
 * moment. The runtime runs it anyway; this record keeps the decision traceable.
 *
 * @param recordedAt when the task was submitted
 * @param recommended the recommended mode, or {@code null} if no mode was suitable
 * @param chosen the mode requested by the task
 * @param chosenVerdict Core's verdict on the chosen mode
 * @param chosenReasons Core's reasons for that verdict
 * @param reason the reason given by the user, or {@code null}
 */
public record StrategyOverride(Instant recordedAt, ExecutionMode recommended, ExecutionMode chosen,
        StrategyAssessment.Verdict chosenVerdict, List<String> chosenReasons, String reason) {

    /** Copies the list. */
    public StrategyOverride {
        chosenReasons = List.copyOf(chosenReasons);
    }
}
