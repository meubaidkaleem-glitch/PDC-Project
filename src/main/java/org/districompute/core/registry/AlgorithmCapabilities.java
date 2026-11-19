package org.districompute.core.registry;

import java.util.List;
import java.util.Objects;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.partition.PartitionStrategyType;

/**
 * Execution capabilities of an algorithm, <em>derived from the interfaces its implementation provides</em>. An
 * algorithm cannot advertise a mode it does not implement.
 *
 * <p>{@code supportedModes} lists modes with a working implementation of this algorithm. Modes that are not
 * {@linkplain ExecutionMode#isLocal() local} also need their runtime to be available: {@code DISTRIBUTED_CPU} needs
 * the distributed runtime with registered workers, and {@code GPU} needs a detected GPU device. Callers combine
 * these capabilities with current resource information before offering a mode.
 *
 * @param supportedModes implemented modes, in {@link ExecutionMode} order
 * @param partitionStrategy partition strategy, or {@code null} if the algorithm is not partitionable
 * @param distributable whether Core provides partition plans, payload codecs and aggregation for distributed use
 * @param gpu whether a detected GPU backend supports this algorithm
 */
public record AlgorithmCapabilities(
        List<ExecutionMode> supportedModes,
        PartitionStrategyType partitionStrategy,
        boolean distributable,
        boolean gpu) {

    /** Validates the components. */
    public AlgorithmCapabilities {
        supportedModes = List.copyOf(supportedModes);
        if (!supportedModes.contains(ExecutionMode.SEQUENTIAL_CPU)) {
            throw new IllegalArgumentException("Every algorithm must support SEQUENTIAL_CPU (the reference baseline)");
        }
        if (distributable != supportedModes.contains(ExecutionMode.DISTRIBUTED_CPU)) {
            throw new IllegalArgumentException("distributable must match DISTRIBUTED_CPU support");
        }
        if (gpu != supportedModes.contains(ExecutionMode.GPU)) {
            throw new IllegalArgumentException("gpu must match GPU support");
        }
        if (distributable) {
            Objects.requireNonNull(partitionStrategy, "partitionStrategy");
        }
    }

    /**
     * Whether a mode is implemented for this algorithm.
     *
     * @param mode execution mode
     * @return {@code true} if supported
     */
    public boolean supports(ExecutionMode mode) {
        return supportedModes.contains(mode);
    }
}
