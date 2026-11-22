package org.districompute.core.algorithm;

import java.util.List;
import java.util.Optional;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStrategyType;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.serialization.PayloadCodec;
import org.districompute.core.validation.Violation;

/**
 * Capability of an algorithm to run as independent partitions. Implementing it enables
 * {@code MULTITHREADED_CPU} (partitions on the local worker pool) and {@code DISTRIBUTED_CPU} (partitions on remote
 * workers, through the payload codecs).
 *
 * <p>Pipeline: {@link #plan} → {@link #extract} (per partition) → {@link #computePartition} → {@link #partialProblem}
 * (validation) → {@link #aggregate}. Local and distributed execution use the same steps. Only the transport
 * differs, so the logic the distributed runtime relies on is exercised by every local parallel test.
 *
 * <p>Contracts:
 * <ul>
 *   <li>{@code plan} is deterministic and covers the input exactly once;</li>
 *   <li>{@code computePartition} is deterministic and free of side effects, so a partition can be retried
 *       anywhere;</li>
 *   <li>{@code extract} may return a view of the input for local execution. The work codec must encode exactly the
 *       data the partition needs, so a decoded work unit computes the same result as the view.</li>
 * </ul>
 *
 * @param <I> input type
 * @param <W> work-unit type
 * @param <P> partial-result type
 * @param <R> result type
 */
public interface PartitionedAlgorithm<I, W, P, R> extends ComputeAlgorithm<I, R> {

    /**
     * How this algorithm partitions its input.
     *
     * @return the strategy
     */
    PartitionStrategyType partitionStrategy();

    /**
     * Checks that the partitioning options make sense for this algorithm.
     *
     * @param parameters validated parameters
     * @param options requested options
     * @return violations; empty if valid
     */
    List<Violation> validatePartitioning(Parameters parameters, PartitioningOptions options);

    /**
     * Plans the partitions from parameters alone, so the plan is known before the input is materialized.
     *
     * @param parameters validated parameters
     * @param options validated options
     * @param defaultPartitions number of partitions to aim for when the options request none
     * @return the bounds of every partition, in index order
     */
    List<PartitionBounds> plan(Parameters parameters, PartitioningOptions options, int defaultPartitions);

    /**
     * Name of the progress unit reported by {@link #computePartition}.
     *
     * @return for example {@code "rows"}
     */
    String workUnit();

    /**
     * Progress units for one partition, in {@link #workUnit()}.
     *
     * @param bounds partition bounds
     * @return the units the partition's kernel will report
     */
    long workUnits(PartitionBounds bounds);

    /**
     * Builds the work unit of one partition.
     *
     * @param input materialized input
     * @param bounds partition bounds
     * @return the work unit (a view for local execution)
     */
    W extract(I input, PartitionBounds bounds);

    /**
     * Computes one partition.
     *
     * @param work work unit
     * @param context execution context; poll cancellation and report progress in {@link #workUnit()}
     * @return the partial result
     */
    P computePartition(W work, ExecutionContext context);

    /**
     * Name of the partial-result type, for example {@code "matrix-block"}.
     *
     * @return the type name
     */
    String partialResultType();

    /**
     * Validates a partial result against the partition it claims to belong to (type, dimensions, bounds and
     * invariants).
     *
     * @param bounds bounds of the partition
     * @param partial received partial result
     * @return a description of the problem, or empty if valid
     */
    Optional<String> partialProblem(PartitionBounds bounds, P partial);

    /**
     * Combines validated partial results.
     *
     * @param plan bounds of every partition, in index order
     * @param partials partial results in the same order
     * @param context execution context
     * @return the final result
     */
    R aggregate(List<PartitionBounds> plan, List<P> partials, ExecutionContext context);

    /**
     * Binary codec for work units.
     *
     * @return the codec
     */
    PayloadCodec<W> workCodec();

    /**
     * Binary codec for partial results.
     *
     * @return the codec
     */
    PayloadCodec<P> partialCodec();
}
