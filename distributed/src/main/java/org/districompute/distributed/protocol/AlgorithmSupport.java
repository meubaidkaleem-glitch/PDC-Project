package org.districompute.distributed.protocol;

import java.util.List;
import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;

/**
 * One algorithm a worker can execute, as reported at registration. The list is derived from the worker's Core
 * registry, never configured by hand.
 *
 * @param algorithm algorithm id and the exact version installed on the worker
 * @param modes modes the worker can run it in; a worker serves {@code DISTRIBUTED_CPU} partitions
 */
public record AlgorithmSupport(AlgorithmRef algorithm, List<ExecutionMode> modes) {

    /** Validates the components. */
    public AlgorithmSupport {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(algorithm.version(), "algorithm.version");
        modes = Checks.boundedCopy(modes, "modes", ExecutionMode.values().length);
    }

    /**
     * Whether the worker reports a mode for this algorithm.
     *
     * @param mode the mode
     * @return {@code true} if supported
     */
    public boolean supports(ExecutionMode mode) {
        return modes.contains(mode);
    }
}
