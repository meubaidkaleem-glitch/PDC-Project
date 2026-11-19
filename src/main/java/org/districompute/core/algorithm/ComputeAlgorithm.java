package org.districompute.core.algorithm;

import java.util.List;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;

/**
 * Service-provider interface implemented by every algorithm. The engine drives an algorithm through these steps:
 * <ol>
 *   <li>schema validation of the parameters (by the engine, using {@link AlgorithmDescriptor#parameters()}),
 *       then {@link #validate} for cross-field and dimension rules;</li>
 *   <li>{@link #analyze} for the workload profile and memory guard, without allocating the input;</li>
 *   <li>{@link #prepareInput}, which deterministically materializes the input;</li>
 *   <li>{@link #computeSequential}, the reference implementation and speedup baseline;</li>
 *   <li>{@link #verify}, an independent check of the result;</li>
 *   <li>{@link #summarize}, a serializable summary and digest of the result.</li>
 * </ol>
 * Parallel and distributed execution are separate capabilities ({@code PartitionedAlgorithm}). Supported execution
 * modes are derived from the interfaces an implementation provides, never declared by hand.
 *
 * <p>Implementations must be stateless and thread-safe: one instance serves concurrent executions.
 *
 * @param <I> materialized input type
 * @param <R> result type
 */
public interface ComputeAlgorithm<I, R> {

    /**
     * Returns the static description of this algorithm.
     *
     * @return the descriptor
     */
    AlgorithmDescriptor descriptor();

    /**
     * Checks rules that span several parameters (for example matching matrix dimensions). Called only after the
     * parameters passed schema validation.
     *
     * @param parameters schema-valid parameters
     * @return violations found; empty if valid
     */
    default List<Violation> validate(Parameters parameters) {
        return List.of();
    }

    /**
     * Estimates the workload's resource requirements from fully validated parameters, without allocating the input.
     *
     * @param parameters validated parameters
     * @return the workload profile
     */
    WorkloadProfile analyze(Parameters parameters);

    /**
     * Materializes the input. The result must be deterministic for given parameters, so the same inputs can be
     * reproduced on any node.
     *
     * @param parameters validated parameters
     * @param context execution context, for cancellation of long-running generation
     * @return the input
     */
    I prepareInput(Parameters parameters, ExecutionContext context);

    /**
     * Computes the result on the calling thread. This is the reference implementation and the speedup baseline.
     *
     * @param input materialized input
     * @param context execution context; poll its cancellation token at bounded intervals
     * @return the result
     */
    R computeSequential(I input, ExecutionContext context);

    /**
     * Verifies a result independently of the computation that produced it.
     *
     * @param input the input the result was computed from
     * @param result the result to verify
     * @param context execution context
     * @return the verification outcome
     */
    VerificationReport verify(I input, R result, ExecutionContext context);

    /**
     * Summarizes a result for serialization, including its canonical digest.
     *
     * @param result the result
     * @return the summary
     */
    ResultSummary summarize(R result);
}
