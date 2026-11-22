package org.districompute.core.execution;

import java.util.List;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.Violation;

/**
 * Validation steps shared by every component that executes tasks: the local engine and the distributed
 * coordinator session. Keeping them in one place guarantees identical input validation whichever component runs
 * the task.
 */
public final class TaskPreparation {

    private static final double MIB = 1024.0 * 1024.0;

    private TaskPreparation() {
    }

    /**
     * Validates a task's parameters against the algorithm: schema, cross-field rules and, for partitioned
     * execution, the partitioning options.
     *
     * @param algorithm resolved algorithm
     * @param spec task specification
     * @param partitioned whether the task will be partitioned
     * @return typed parameters
     * @throws ValidationException listing every violation
     */
    public static Parameters validate(ComputeAlgorithm<?, ?> algorithm, TaskSpec spec, boolean partitioned) {
        Parameters parameters = ParameterValidator.validate(algorithm.descriptor().parameters(), spec.parameters());
        List<Violation> semantic = algorithm.validate(parameters);
        if (!semantic.isEmpty()) {
            throw new ValidationException(semantic);
        }
        if (partitioned) {
            if (!(algorithm instanceof PartitionedAlgorithm<?, ?, ?, ?> partitionable)) {
                throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE,
                        algorithm.descriptor().id() + " cannot be partitioned");
            }
            List<Violation> partitioning = partitionable.validatePartitioning(parameters,
                    spec.constraints().partitioning());
            if (!partitioning.isEmpty()) {
                throw new ValidationException(partitioning);
            }
        }
        return parameters;
    }

    /**
     * Rejects a workload whose estimated peak memory exceeds the budget
     * {@code min(cap, (maxHeap − usedHeap) × headroom)}.
     *
     * @param profile workload profile
     * @param cap task memory cap in bytes, or {@code null}
     * @param headroom fraction of the free heap a task may plan to use
     * @throws DistriComputeException with {@link ErrorCode#RESOURCE_EXHAUSTED}
     */
    public static void checkMemory(WorkloadProfile profile, Long cap, double headroom) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long budget = (long) ((runtime.maxMemory() - used) * headroom);
        if (cap != null) {
            budget = Math.min(budget, cap);
        }
        long needed = profile.estimatedPeakMemoryBytes();
        if (needed > budget) {
            throw new DistriComputeException(ErrorCode.RESOURCE_EXHAUSTED, String.format(
                    "Estimated peak memory %.1f MiB exceeds the budget of %.1f MiB (max heap %.1f MiB%s). "
                            + "Reduce the problem size, raise -Xmx, or run it on more nodes.",
                    needed / MIB, budget / MIB, runtime.maxMemory() / MIB,
                    cap == null ? "" : String.format(", task cap %.1f MiB", cap / MIB)));
        }
    }
}
