package org.districompute.core.execution;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.metrics.EnvironmentInfo;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.metrics.ResourceUsage;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.registry.AlgorithmCapabilities;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.TaskLifecycle;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.ValidationException;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes tasks on the local node and drives each one through the full lifecycle: validation (schema, semantic
 * rules, mode support, memory guard), planning, execution, aggregation and independent verification.
 *
 * <p>{@link #execute} never throws for task-level problems. Every outcome, including invalid input, cancellation,
 * timeouts and verification failures, is returned as a {@link TaskResult} with a terminal status and an
 * {@link ErrorInfo}. A task is {@code COMPLETED} only after its result passed verification.
 *
 * <p>The engine is thread-safe. At most {@link EngineConfig#maxConcurrentTasks()} tasks run at once. Further tasks
 * wait in {@code QUEUED} (fair, first come first served) until a slot frees or they are cancelled. The engine owns
 * a bounded {@link WorkerPool} and a timer thread for deadlines, so close it when done.
 */
public final class LocalExecutionEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalExecutionEngine.class);
    private static final double MIB = 1024.0 * 1024.0;

    private final AlgorithmRegistry registry;
    private final EngineConfig config;
    private final EnvironmentInfo environment;
    private final ScheduledExecutorService deadlines;
    private final WorkerPool pool;
    private final Semaphore admission;
    private final Set<CancellationSource> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Creates an engine.
     *
     * @param registry algorithms available to this engine
     * @param config engine configuration
     */
    public LocalExecutionEngine(AlgorithmRegistry registry, EngineConfig config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.config = Objects.requireNonNull(config, "config");
        this.environment = EnvironmentInfo.capture();
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "districompute-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        timer.setRemoveOnCancelPolicy(true);
        this.deadlines = timer;
        this.pool = new WorkerPool(config.maxThreads(), config.maxConcurrentTasks(), "districompute-worker");
        this.admission = new Semaphore(config.maxConcurrentTasks(), true);
    }

    /**
     * Creates an engine with the built-in algorithms and the default configuration.
     *
     * @return the engine
     */
    public static LocalExecutionEngine withDefaults() {
        return new LocalExecutionEngine(AlgorithmRegistry.loadDefault(), EngineConfig.defaults());
    }

    /**
     * Returns the algorithms available to this engine.
     *
     * @return the registry
     */
    public AlgorithmRegistry registry() {
        return registry;
    }

    /**
     * Returns the engine configuration.
     *
     * @return the configuration
     */
    public EngineConfig config() {
        return config;
    }

    /** Returns the worker pool (for boundedness checks in tests). */
    WorkerPool workerPool() {
        return pool;
    }

    /**
     * Returns the environment this engine measures in.
     *
     * @return the environment snapshot taken at construction
     */
    public EnvironmentInfo environment() {
        return environment;
    }

    /**
     * Executes a task that can only be cancelled by its deadline.
     *
     * @param spec task specification
     * @return the outcome
     */
    public ExecutionOutcome execute(TaskSpec spec) {
        return execute(spec, new CancellationSource());
    }

    /**
     * Executes a task.
     *
     * @param spec task specification
     * @param cancellation cancel this source to cancel the task; use one source per task
     * @return the outcome; never {@code null}
     * @throws IllegalStateException if the engine is closed
     */
    public ExecutionOutcome execute(TaskSpec spec, CancellationSource cancellation) {
        return execute(spec, cancellation, ProgressListener.NONE);
    }

    /**
     * Executes a task and reports its progress.
     *
     * @param spec task specification
     * @param cancellation cancel this source to cancel the task; use one source per task
     * @param listener receives throttled, monotonic progress events, including every status change
     * @return the outcome; never {@code null}
     * @throws IllegalStateException if the engine is closed
     */
    public ExecutionOutcome execute(TaskSpec spec, CancellationSource cancellation, ProgressListener listener) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(listener, "listener");
        if (closed.get()) {
            throw new IllegalStateException("The engine is closed");
        }
        CancellationSource scope = cancellation.newChild();
        active.add(scope);
        try {
            return new Run(spec, scope, listener).execute();
        } finally {
            active.remove(scope);
        }
    }

    /**
     * Shuts the engine down. Running and queued tasks are cancelled with {@link CancellationReason#SHUTDOWN} and end
     * {@code CANCELLED}. The worker pool and the deadline timer are then stopped. Further {@code execute} calls throw.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            for (CancellationSource scope : active) {
                scope.cancel(CancellationReason.SHUTDOWN);
            }
            pool.close();
            deadlines.shutdownNow();
        }
    }

    /** State and steps of one execution. */
    private final class Run {
        private final TaskSpec spec;
        private final CancellationSource scope;
        private final TaskLifecycle lifecycle;
        private final TaskProgress progress;
        private AlgorithmRef resolved;
        private WorkloadProfile profile;
        private VerificationReport verification;
        private ResultSummary summary;
        private Object value;
        private long validationNanos;
        private long preparationNanos;
        private long computeNanos;
        private long aggregationNanos;
        private long executionNanos;
        private long verificationNanos;
        private long busyNanos;
        private int threads;
        private int partitions;
        private ResourceUsage usage;

        Run(TaskSpec spec, CancellationSource scope, ProgressListener listener) {
            this.spec = spec;
            this.scope = scope;
            this.lifecycle = new TaskLifecycle(config.clock());
            this.progress = new TaskProgress(spec.taskId(), listener, config.progressIntervalMillis(), config.clock());
        }

        private void transition(TaskStatus status) {
            lifecycle.transitionTo(status);
            progress.status(status);
        }

        ExecutionOutcome execute() {
            ScheduledFuture<?> deadline = scheduleDeadline();
            boolean admitted = false;
            try {
                progress.status(TaskStatus.CREATED);
                transition(TaskStatus.VALIDATING);
                long validationStart = System.nanoTime();
                ComputeAlgorithm<?, ?> algorithm = registry.resolve(spec.algorithm());
                resolved = algorithm.descriptor().ref();
                checkMode();
                Parameters parameters = ParameterValidator.validate(algorithm.descriptor().parameters(),
                        spec.parameters());
                List<Violation> semantic = algorithm.validate(parameters);
                if (!semantic.isEmpty()) {
                    throw new ValidationException(semantic);
                }
                profile = algorithm.analyze(parameters);
                checkMemory(profile);
                validationNanos = System.nanoTime() - validationStart;
                transition(TaskStatus.VALIDATED);
                threads = 1;
                partitions = 1;
                transition(TaskStatus.PLANNED);
                transition(TaskStatus.QUEUED);
                admitted = awaitAdmission();
                transition(TaskStatus.RUNNING);
                runSequential(algorithm, parameters);
                transition(TaskStatus.COMPLETED);
                return outcome(null);
            } catch (TaskCancelledException e) {
                return terminate(e.reason() == CancellationReason.TIMEOUT ? TaskStatus.FAILED : TaskStatus.CANCELLED,
                        e);
            } catch (DistriComputeException e) {
                return terminate(TaskStatus.FAILED, e);
            } catch (OutOfMemoryError e) {
                return terminate(TaskStatus.FAILED, new DistriComputeException(ErrorCode.RESOURCE_EXHAUSTED,
                        "Out of memory during execution; the memory estimate was too optimistic", e));
            } catch (RuntimeException | StackOverflowError e) {
                LOG.warn("Task {} failed unexpectedly", spec.taskId(), e);
                return terminate(TaskStatus.FAILED, new DistriComputeException(ErrorCode.EXECUTION_FAILED,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), e));
            } finally {
                if (admitted) {
                    admission.release();
                }
                if (deadline != null) {
                    deadline.cancel(false);
                }
            }
        }

        /** Waits for an execution slot while staying responsive to cancellation. */
        private boolean awaitAdmission() {
            try {
                while (!admission.tryAcquire(20, TimeUnit.MILLISECONDS)) {
                    scope.throwIfCancelled();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scope.cancel(CancellationReason.USER_REQUESTED);
                scope.throwIfCancelled();
            }
            if (scope.isCancelled()) {
                admission.release();
                scope.throwIfCancelled();
            }
            return true;
        }

        private <I, R> void runSequential(ComputeAlgorithm<I, R> algorithm, Parameters parameters) {
            ExecutionContext context = ExecutionContext.of(scope, progress);
            long preparationStart = System.nanoTime();
            I input = algorithm.prepareInput(parameters, context);
            preparationNanos = System.nanoTime() - preparationStart;

            ResourceUsage before = ResourceUsage.capture();
            long executionStart = System.nanoTime();
            R result = algorithm.computeSequential(input, context);
            long computeEnd = System.nanoTime();
            transition(TaskStatus.AGGREGATING);
            long executionEnd = System.nanoTime();
            usage = ResourceUsage.capture().minus(before);
            computeNanos = computeEnd - executionStart;
            busyNanos = computeNanos;
            aggregationNanos = executionEnd - computeEnd;
            executionNanos = executionEnd - executionStart;

            transition(TaskStatus.VERIFYING);
            long verificationStart = System.nanoTime();
            verification = algorithm.verify(input, result, context);
            verificationNanos = System.nanoTime() - verificationStart;
            summary = algorithm.summarize(result);
            if (!verification.passed()) {
                throw new DistriComputeException(ErrorCode.VERIFICATION_FAILED, verification.message());
            }
            value = result;
        }

        private void checkMode() {
            ExecutionMode mode = spec.constraints().mode();
            AlgorithmCapabilities capabilities = registry.info(resolved.id()).orElseThrow().capabilities();
            if (!mode.isLocal()) {
                throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE, mode
                        + " is not executed by the local engine; submit the task to the component that provides it");
            }
            if (!capabilities.supports(mode)) {
                throw new DistriComputeException(ErrorCode.UNSUPPORTED_EXECUTION_MODE, mode
                        + " is not implemented for " + resolved.id() + "; supported: " + capabilities.supportedModes());
            }
            if (mode == ExecutionMode.SEQUENTIAL_CPU) {
                Integer requestedThreads = spec.constraints().maxThreads();
                if (requestedThreads != null && requestedThreads != 1) {
                    throw new ValidationException(new Violation("constraints.maxThreads", Violation.Codes.INCONSISTENT,
                            "SEQUENTIAL_CPU runs on exactly one thread"));
                }
                if (!PartitioningOptions.defaults().equals(spec.constraints().partitioning())) {
                    throw new ValidationException(new Violation("constraints.partitioning",
                            Violation.Codes.INCONSISTENT, "SEQUENTIAL_CPU does not partition the input"));
                }
            }
        }

        private void checkMemory(WorkloadProfile workload) {
            Runtime runtime = Runtime.getRuntime();
            long used = runtime.totalMemory() - runtime.freeMemory();
            long budget = (long) ((runtime.maxMemory() - used) * config.memoryHeadroom());
            Long cap = spec.constraints().maxMemoryBytes();
            if (cap != null) {
                budget = Math.min(budget, cap);
            }
            long needed = workload.estimatedPeakMemoryBytes();
            if (needed > budget) {
                throw new DistriComputeException(ErrorCode.RESOURCE_EXHAUSTED, String.format(
                        "Estimated peak memory %.1f MiB exceeds the budget of %.1f MiB (max heap %.1f MiB%s). "
                                + "Reduce the problem size, raise -Xmx, or run it on more nodes.",
                        needed / MIB, budget / MIB, runtime.maxMemory() / MIB,
                        cap == null ? "" : String.format(", task cap %.1f MiB", cap / MIB)));
            }
        }

        private ScheduledFuture<?> scheduleDeadline() {
            Long timeout = spec.constraints().timeoutMillis();
            if (timeout == null) {
                return null;
            }
            return deadlines.schedule(() -> scope.cancel(CancellationReason.TIMEOUT), timeout, TimeUnit.MILLISECONDS);
        }

        private ExecutionOutcome terminate(TaskStatus status, DistriComputeException failure) {
            ErrorInfo error = failure.toErrorInfo(lifecycle.current());
            if (lifecycle.tryTransitionTo(status)) {
                progress.status(status);
            } else {
                LOG.warn("Task {} could not move from {} to {}", spec.taskId(), lifecycle.current(), status);
            }
            LOG.debug("Task {} ended {}: {}", spec.taskId(), status, error.message());
            return outcome(error);
        }

        private ExecutionOutcome outcome(ErrorInfo error) {
            ResourceUsage measured = usage == null ? new ResourceUsage(-1, -1, -1, -1) : usage;
            ExecutionMetrics metrics = new ExecutionMetrics(validationNanos, preparationNanos, 0, computeNanos,
                    aggregationNanos, executionNanos, verificationNanos, busyNanos,
                    ExecutionMetrics.schedulingOverhead(computeNanos, busyNanos, threads), threads, partitions,
                    measured.processCpuNanos(), measured.gcCount(), measured.gcMillis(),
                    measured.threadAllocatedBytes());
            TaskStatus status = lifecycle.current();
            TaskResult result = new TaskResult(SchemaVersion.CURRENT, spec.taskId(), spec.experimentId(),
                    resolved != null ? resolved : spec.algorithm(), status, spec.constraints().mode(), threads,
                    partitions, summary, verification, profile, metrics, environment, lifecycle.history(), error);
            return new ExecutionOutcome(result, status == TaskStatus.COMPLETED ? value : null);
        }
    }
}
