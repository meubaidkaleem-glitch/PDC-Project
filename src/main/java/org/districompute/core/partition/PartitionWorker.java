package org.districompute.core.partition;

import java.util.Objects;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.serialization.SchemaVersion;

/**
 * Worker-side entry point for distributed execution: decodes a work unit, computes the partition with the same
 * kernel as local execution, and encodes the partial result. It has no networking, threads or state of its own.
 * The distributed runtime calls {@link #execute} on whatever thread it chooses.
 */
public final class PartitionWorker {

    private final AlgorithmRegistry registry;
    private final String workerId;

    /**
     * Creates a worker.
     *
     * @param registry algorithms available on this worker
     * @param workerId identifier reported in partial results
     */
    public PartitionWorker(AlgorithmRegistry registry, String workerId) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
    }

    /**
     * Computes one partition.
     *
     * @param message work unit from the coordinator
     * @param cancellation polled by the kernel
     * @param progress receives kernel progress in the algorithm's work unit
     * @return the encoded partial result
     * @throws DistriComputeException with {@link ErrorCode#UNKNOWN_ALGORITHM},
     *     {@link ErrorCode#UNSUPPORTED_ALGORITHM_VERSION}, {@link ErrorCode#SERIALIZATION_FAILED},
     *     {@link ErrorCode#CANCELLED} or {@link ErrorCode#PARTITION_FAILED}
     */
    public PartialResultMessage execute(WorkUnitMessage message, CancellationToken cancellation,
            ProgressReporter progress) {
        PartitionDescriptor descriptor = message.descriptor();
        ComputeAlgorithm<?, ?> algorithm = registry.resolve(descriptor.algorithm());
        if (!(algorithm instanceof PartitionedAlgorithm<?, ?, ?, ?> partitioned)) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE,
                    descriptor.algorithm() + " cannot be partitioned on this worker");
        }
        return run(partitioned, message, ExecutionContext.of(cancellation, progress));
    }

    private <W, P> PartialResultMessage run(PartitionedAlgorithm<?, W, P, ?> algorithm, WorkUnitMessage message,
            ExecutionContext context) {
        PartitionDescriptor descriptor = message.descriptor();
        if (!algorithm.workCodec().format().equals(message.payload().format())) {
            throw new DistriComputeException(ErrorCode.SERIALIZATION_FAILED, "Work unit format "
                    + message.payload().format() + " is not " + algorithm.workCodec().format());
        }
        W work = algorithm.workCodec().decode(message.payload().decodeBytes());
        long started = System.nanoTime();
        P partial;
        try {
            partial = algorithm.computePartition(work, context);
        } catch (DistriComputeException e) {
            throw e;
        } catch (RuntimeException e) {
            throw DistriComputeException.forPartition(ErrorCode.PARTITION_FAILED, descriptor.index(),
                    "Partition " + descriptor.partitionId() + " failed on " + workerId + ": " + e, e);
        }
        long computeNanos = System.nanoTime() - started;
        EncodedPayload payload = EncodedPayload.of(algorithm.partialCodec().format(),
                algorithm.partialCodec().encode(partial));
        return new PartialResultMessage(SchemaVersion.CURRENT, descriptor.taskId(), descriptor.partitionId(),
                descriptor.index(), message.attempt(), algorithm.partialResultType(), payload, computeNanos, workerId);
    }
}
