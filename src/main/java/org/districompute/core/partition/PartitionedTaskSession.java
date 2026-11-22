package org.districompute.core.partition;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.CancellationSource;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.execution.TaskPreparation;
import org.districompute.core.metrics.EnvironmentInfo;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.DigestBuilder;
import org.districompute.core.result.PartialResultCollector;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.EncodedPayload;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.TaskLifecycle;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.VerificationReport;

/**
 * Coordinator-side entry point for distributed execution of one task ({@code DISTRIBUTED_CPU}). The distributed
 * runtime owns networking, worker selection, dispatch and retry policy. This session owns everything
 * algorithm-specific:
 * <ol>
 *   <li>{@link #open}: validation (identical to the local engine), workload analysis, memory guard, partition
 *       plan and input preparation;</li>
 *   <li>{@link #workUnit}: encodes the work unit of a partition for any attempt;</li>
 *   <li>{@link #accept}: decodes and classifies a partial result ({@link PartialResultCollector.Classification});
 *       {@link #markFailed} records failed attempts;</li>
 *   <li>{@link #complete}: aggregates and independently verifies the result once every partition is present.
 *       A task is only {@code COMPLETED} if verification passes.</li>
 * </ol>
 * The session is thread-safe: {@code workUnit} and {@code accept} may be called concurrently.
 *
 * <p>Metrics semantics differ from local execution: {@code computeNanos} is the wall time the coordinator observed
 * from the first dispatch to the last accepted partial result, so it <em>includes</em> transfer and remote
 * scheduling. {@code busyNanos} is the sum of the kernel times reported by workers. The distributed runtime must
 * report communication separately.
 */
public final class PartitionedTaskSession {

    private final Typed<?, ?, ?, ?> typed;

    private PartitionedTaskSession(Typed<?, ?, ?, ?> typed) {
        this.typed = typed;
    }

    /**
     * Validates and prepares a task for distributed execution.
     *
     * @param registry algorithms available to the coordinator
     * @param spec task specification; its mode must be {@link ExecutionMode#DISTRIBUTED_CPU}
     * @param defaultPartitions partitions to plan when the task requests none (for example twice the number of
     *     workers)
     * @return the session, in status {@code QUEUED}
     * @throws DistriComputeException if the task is invalid (same codes as the local engine)
     */
    public static PartitionedTaskSession open(AlgorithmRegistry registry, TaskSpec spec, int defaultPartitions) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(spec, "spec");
        if (defaultPartitions < 1) {
            throw new IllegalArgumentException("defaultPartitions must be at least 1");
        }
        if (spec.constraints().mode() != ExecutionMode.DISTRIBUTED_CPU) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE,
                    "A distributed session runs DISTRIBUTED_CPU tasks, not " + spec.constraints().mode());
        }
        ComputeAlgorithm<?, ?> algorithm = registry.resolve(spec.algorithm());
        if (!(algorithm instanceof PartitionedAlgorithm<?, ?, ?, ?> partitioned)) {
            throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE,
                    algorithm.descriptor().id() + " cannot be partitioned");
        }
        return new PartitionedTaskSession(new Typed<>(partitioned, spec, defaultPartitions));
    }

    /**
     * Current task status.
     *
     * @return the status
     */
    public TaskStatus status() {
        return typed.lifecycle.current();
    }

    /**
     * Workload profile computed during validation.
     *
     * @return the profile
     */
    public WorkloadProfile profile() {
        return typed.profile;
    }

    /**
     * Descriptors of all partitions, in index order.
     *
     * @return the descriptors
     */
    public List<PartitionDescriptor> partitions() {
        return typed.descriptors;
    }

    /**
     * Encodes the work unit of a partition. The first call moves the task to {@code RUNNING}.
     *
     * @param index partition index
     * @param attempt attempt number, starting at 1
     * @return the message to send to a worker
     */
    public WorkUnitMessage workUnit(int index, int attempt) {
        return typed.workUnit(index, attempt);
    }

    /**
     * Accepts a partial result from a worker. Undecodable, foreign, misplaced and invalid results are classified,
     * never silently dropped.
     *
     * @param message the worker's message
     * @return the classification
     */
    public PartialResultCollector.Offer accept(PartialResultMessage message) {
        return typed.accept(message);
    }

    /**
     * Records a failed attempt (for example a lost worker). The partition stays missing until a later attempt
     * succeeds.
     *
     * @param index partition index
     * @param error failure description
     */
    public void markFailed(int index, ErrorInfo error) {
        typed.collector.markFailed(index, error);
    }

    /**
     * Whether a partition may be dispatched again: it is retryable and has no accepted result yet.
     *
     * @param index partition index
     * @return {@code true} if a retry is eligible
     */
    public boolean retryEligible(int index) {
        return typed.descriptors.get(index).retryable()
                && typed.collector.status(index) != PartitionStatus.COMPLETED;
    }

    /**
     * Partitions without an accepted result.
     *
     * @return indices in ascending order
     */
    public List<Integer> missing() {
        return typed.collector.missing();
    }

    /**
     * Whether every partition has an accepted result.
     *
     * @return {@code true} if ready to complete
     */
    public boolean isComplete() {
        return typed.collector.isComplete();
    }

    /**
     * Status of one partition.
     *
     * @param index partition index
     * @return the status
     */
    public PartitionStatus partitionStatus(int index) {
        return typed.collector.status(index);
    }

    /**
     * Aggregates and verifies the result.
     *
     * @return a {@code COMPLETED} result, or {@code FAILED} if verification fails
     * @throws DistriComputeException with {@link ErrorCode#MISSING_PARTIAL_RESULT} (the session stays usable, so
     *     retry the missing partitions and call again)
     */
    public TaskResult complete() {
        return typed.complete();
    }

    /**
     * Ends the task as failed, for example when a partition fails permanently.
     *
     * @param error the failure
     * @return the {@code FAILED} result
     */
    public TaskResult fail(ErrorInfo error) {
        return typed.terminate(TaskStatus.FAILED, error);
    }

    /**
     * Ends the task as cancelled.
     *
     * @param reason why
     * @return the {@code CANCELLED} result
     */
    public TaskResult cancel(CancellationReason reason) {
        typed.scope.cancel(reason);
        return typed.terminate(TaskStatus.CANCELLED,
                new ErrorInfo(ErrorCode.CANCELLED, "Cancelled: " + reason, false, typed.lifecycle.current(), null,
                        List.of()));
    }

    /**
     * The full result value, once the task has completed.
     *
     * @return the value, if completed
     */
    public Optional<Object> value() {
        return Optional.ofNullable(typed.completedValue());
    }

    /** Type-safe internals of the session. */
    private static final class Typed<I, W, P, R> {
        private final PartitionedAlgorithm<I, W, P, R> algorithm;
        private final TaskSpec spec;
        private final TaskLifecycle lifecycle = new TaskLifecycle(Clock.systemUTC());
        private final CancellationSource scope = new CancellationSource();
        private final ExecutionContext context = ExecutionContext.of(scope, ProgressReporter.NONE);
        private final EnvironmentInfo environment = EnvironmentInfo.capture();
        private final AlgorithmRef resolved;
        private final WorkloadProfile profile;
        private final List<PartitionBounds> plan;
        private final List<PartitionDescriptor> descriptors;
        private final PartialResultCollector<P> collector;
        private final I input;
        private final Set<String> workers = new HashSet<>();
        private final long validationNanos;
        private final long preparationNanos;
        private long partitioningNanos;
        private long busyNanos;
        private long firstDispatchNanos = -1;
        private long lastAcceptNanos = -1;
        private VerificationReport verification;
        private ResultSummary summary;
        private Object value;
        private ExecutionMetrics finalMetrics;

        Typed(PartitionedAlgorithm<I, W, P, R> algorithm, TaskSpec spec, int defaultPartitions) {
            this.algorithm = algorithm;
            this.spec = spec;
            this.resolved = algorithm.descriptor().ref();
            lifecycle.transitionTo(TaskStatus.VALIDATING);
            long start = System.nanoTime();
            Parameters parameters = TaskPreparation.validate(algorithm, spec, true);
            this.profile = algorithm.analyze(parameters);
            TaskPreparation.checkMemory(profile, spec.constraints().maxMemoryBytes(), 0.8);
            this.validationNanos = System.nanoTime() - start;
            lifecycle.transitionTo(TaskStatus.VALIDATED);
            long planStart = System.nanoTime();
            this.plan = algorithm.plan(parameters, spec.constraints().partitioning(), defaultPartitions);
            List<PartitionDescriptor> list = new ArrayList<>(plan.size());
            for (int i = 0; i < plan.size(); i++) {
                list.add(new PartitionDescriptor(SchemaVersion.CURRENT, PartitionDescriptor.idOf(spec.taskId(), i),
                        spec.taskId(), resolved, i, plan.size(), plan.get(i), algorithm.partialResultType(), true));
            }
            this.descriptors = List.copyOf(list);
            this.partitioningNanos = System.nanoTime() - planStart;
            this.collector = new PartialResultCollector<>(spec.taskId(), plan, algorithm::partialProblem,
                    partial -> new DigestBuilder("partial").putBytes(algorithm.partialCodec().encode(partial))
                            .finish());
            lifecycle.transitionTo(TaskStatus.PLANNED);
            long preparationStart = System.nanoTime();
            this.input = algorithm.prepareInput(parameters, context);
            this.preparationNanos = System.nanoTime() - preparationStart;
            lifecycle.transitionTo(TaskStatus.QUEUED);
        }

        WorkUnitMessage workUnit(int index, int attempt) {
            synchronized (this) {
                requireActive();
                if (lifecycle.current() == TaskStatus.QUEUED) {
                    lifecycle.transitionTo(TaskStatus.RUNNING);
                    firstDispatchNanos = System.nanoTime();
                }
            }
            long start = System.nanoTime();
            PartitionDescriptor descriptor = descriptors.get(Objects.checkIndex(index, descriptors.size()));
            W work = algorithm.extract(input, descriptor.bounds());
            EncodedPayload payload = EncodedPayload.of(algorithm.workCodec().format(),
                    algorithm.workCodec().encode(work));
            synchronized (this) {
                partitioningNanos += System.nanoTime() - start;
            }
            return new WorkUnitMessage(descriptor, attempt, payload);
        }

        PartialResultCollector.Offer accept(PartialResultMessage message) {
            synchronized (this) {
                if (lifecycle.current().isTerminal()) {
                    return new PartialResultCollector.Offer(PartialResultCollector.Classification.LATE,
                            "task is already " + lifecycle.current());
                }
            }
            int index = message.index();
            if (index < 0 || index >= descriptors.size()) {
                return collector.offer(message.taskId(), index, null);
            }
            PartitionDescriptor descriptor = descriptors.get(index);
            String mismatch = !descriptor.partitionId().equals(message.partitionId()) ? "partitionId mismatch"
                    : !algorithm.partialResultType().equals(message.resultType()) ? "unexpected resultType "
                            + message.resultType()
                    : !algorithm.partialCodec().format().equals(message.payload().format()) ? "unexpected payload "
                            + "format " + message.payload().format() : null;
            if (mismatch != null) {
                return invalid(message, mismatch);
            }
            P partial;
            try {
                partial = algorithm.partialCodec().decode(message.payload().decodeBytes());
            } catch (DistriComputeException e) {
                return invalid(message, e.getMessage());
            }
            PartialResultCollector.Offer offer = collector.offer(message.taskId(), index, partial);
            if (offer.accepted()) {
                synchronized (this) {
                    busyNanos += Math.max(0, message.computeNanos());
                    workers.add(message.workerId());
                    lastAcceptNanos = System.nanoTime();
                }
            }
            return offer;
        }

        private PartialResultCollector.Offer invalid(PartialResultMessage message, String detail) {
            PartialResultCollector.Offer offer = collector.offer(message.taskId(), message.index(), null);
            return offer.classification() == PartialResultCollector.Classification.INVALID
                    ? new PartialResultCollector.Offer(PartialResultCollector.Classification.INVALID, detail) : offer;
        }

        synchronized TaskResult complete() {
            requireActive();
            List<P> partials = collector.orderedResults();
            lifecycle.transitionTo(TaskStatus.AGGREGATING);
            long aggregationStart = System.nanoTime();
            R result = algorithm.aggregate(plan, partials, context);
            long aggregationNanos = System.nanoTime() - aggregationStart;
            collector.close();
            lifecycle.transitionTo(TaskStatus.VERIFYING);
            long verificationStart = System.nanoTime();
            verification = algorithm.verify(input, result, context);
            long verificationNanos = System.nanoTime() - verificationStart;
            summary = algorithm.summarize(result);
            long computeNanos = firstDispatchNanos < 0 ? 0 : Math.max(0, lastAcceptNanos - firstDispatchNanos);
            int threads = Math.max(1, workers.size());
            finalMetrics = new ExecutionMetrics(validationNanos, preparationNanos, partitioningNanos, computeNanos,
                    aggregationNanos, partitioningNanos + computeNanos + aggregationNanos, verificationNanos,
                    busyNanos, ExecutionMetrics.schedulingOverhead(computeNanos, busyNanos, threads), threads,
                    plan.size(), -1, -1, -1, -1);
            if (!verification.passed()) {
                return terminate(TaskStatus.FAILED, new ErrorInfo(ErrorCode.VERIFICATION_FAILED,
                        verification.message(), true, TaskStatus.VERIFYING, null, List.of()));
            }
            lifecycle.transitionTo(TaskStatus.COMPLETED);
            value = result;
            return result(null);
        }

        synchronized TaskResult terminate(TaskStatus status, ErrorInfo error) {
            requireActive();
            ErrorInfo located = error.status() != null ? error : new ErrorInfo(error.code(), error.message(),
                    error.retryable(), lifecycle.current(), error.partitionIndex(), error.violations());
            collector.close();
            lifecycle.transitionTo(status);
            return result(located);
        }

        private TaskResult result(ErrorInfo error) {
            ExecutionMetrics metrics = finalMetrics != null ? finalMetrics : new ExecutionMetrics(validationNanos,
                    preparationNanos, partitioningNanos, 0, 0, 0, 0, busyNanos, 0, Math.max(1, workers.size()),
                    plan.size(), -1, -1, -1, -1);
            return new TaskResult(SchemaVersion.CURRENT, spec.taskId(), spec.experimentId(), resolved,
                    lifecycle.current(), ExecutionMode.DISTRIBUTED_CPU, metrics.threads(), plan.size(), summary,
                    verification, profile, metrics, environment, lifecycle.history(), error);
        }

        synchronized Object completedValue() {
            return value;
        }

        private void requireActive() {
            if (lifecycle.current().isTerminal()) {
                throw new IllegalStateException("Task " + spec.taskId() + " is already " + lifecycle.current());
            }
        }
    }
}
