package org.districompute.distributed.task;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.districompute.core.ErrorInfo;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.metrics.EnvironmentInfo;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.partition.PartitionDescriptor;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.partition.PartitionedTaskSession;
import org.districompute.core.result.TaskResult;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.TaskLifecycle;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.metrics.MetricsCalculator;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.scheduler.TaskRequirements;
import org.districompute.distributed.strategy.StrategyOverride;

/**
 * Mutable state of one task in the runtime. Owned by the coordinator's control loop. The task status is Core's:
 * the runtime's own lifecycle covers submission and planning, then Core's session drives it.
 */
public final class DistributedTask {

    private final TaskSpec spec;
    private final PolicyName policy;
    private final int maxAttempts;
    private final long attemptTimeoutMillis;
    private final Integer maxWorkers;
    private final Instant submittedAt;
    private final long submittedNanos;
    private final TaskLifecycle lifecycle;
    private final CompletableFuture<TaskOutcome> completion = new CompletableFuture<>();
    private final Map<String, Attempt> attemptsById = new LinkedHashMap<>();
    private final Set<String> workersUsed = new LinkedHashSet<>();
    private PartitionedTaskSession session;
    private TaskRequirements requirements;
    private List<PartitionState> partitions = List.of();
    private long planningNanos;
    private long firstDispatchNanos = -1;
    private long lastAcceptedNanos = -1;
    private long aggregationNanos;
    private long schedulingNanos;
    private Instant finishedAt;
    private long finishedNanos = -1;
    private TaskResult result;
    private boolean completing;
    private TaskStatus publishedStatus;
    private StrategyOverride override;

    /**
     * Creates a submitted task.
     *
     * @param spec Core task specification
     * @param policy scheduling policy
     * @param maxAttempts attempts per partition
     * @param attemptTimeoutMillis time allowed for one attempt
     * @param maxWorkers distinct worker limit, or {@code null}
     * @param clock clock for timestamps
     */
    public DistributedTask(TaskSpec spec, PolicyName policy, int maxAttempts, long attemptTimeoutMillis,
            Integer maxWorkers, Clock clock) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.maxAttempts = maxAttempts;
        this.attemptTimeoutMillis = attemptTimeoutMillis;
        this.maxWorkers = maxWorkers;
        this.lifecycle = new TaskLifecycle(clock);
        this.submittedAt = clock.instant();
        this.submittedNanos = System.nanoTime();
    }

    /**
     * Installs the opened Core session and creates the partition states.
     *
     * @param opened Core's session, in status {@code QUEUED}
     * @param taskRequirements per-attempt requirements
     * @param openNanos time Core took to open the session
     */
    public void planned(PartitionedTaskSession opened, TaskRequirements taskRequirements, long openNanos) {
        this.session = opened;
        this.requirements = taskRequirements;
        this.planningNanos = openNanos;
        List<PartitionState> states = new ArrayList<>();
        for (PartitionDescriptor descriptor : opened.partitions()) {
            states.add(new PartitionState(descriptor));
        }
        this.partitions = List.copyOf(states);
    }

    /**
     * Marks the start of planning (Core validation runs).
     */
    public void validating() {
        if (lifecycle.current() == TaskStatus.CREATED) {
            lifecycle.transitionTo(TaskStatus.VALIDATING);
        }
    }

    /**
     * Records a dispatched attempt.
     *
     * @param partition its partition
     * @param attempt the attempt
     */
    public void addAttempt(PartitionState partition, Attempt attempt) {
        partition.add(attempt);
        attemptsById.put(attempt.ref().attemptId(), attempt);
        workersUsed.add(attempt.workerId());
        if (firstDispatchNanos < 0) {
            firstDispatchNanos = attempt.decidedNanos();
        }
    }

    /**
     * Looks up an attempt.
     *
     * @param attemptId attempt id
     * @return the attempt, or {@code null}
     */
    public Attempt attempt(String attemptId) {
        return attemptsById.get(attemptId);
    }

    /**
     * All attempts in dispatch order.
     *
     * @return the attempts
     */
    public List<Attempt> attempts() {
        return List.copyOf(attemptsById.values());
    }

    /**
     * Records that a partition's result was accepted.
     *
     * @param partition the partition
     * @param nanos monotonic time
     */
    public void partitionCompleted(PartitionState partition, long nanos) {
        partition.setStatus(PartitionStatus.COMPLETED);
        lastAcceptedNanos = nanos;
    }

    /**
     * Adds scheduling time.
     *
     * @param nanos time spent on a decision and its bookkeeping
     */
    public void addSchedulingNanos(long nanos) {
        schedulingNanos += nanos;
    }

    /**
     * Records the aggregation and verification time.
     *
     * @param nanos the duration
     */
    public void setAggregationNanos(long nanos) {
        this.aggregationNanos = nanos;
    }

    /**
     * Ends the task with Core's result and completes its future.
     *
     * @param taskResult Core's terminal result
     * @param at wall-clock time
     * @param metricsCalculator computes the final metrics
     */
    public void finish(TaskResult taskResult, Instant at, MetricsCalculator metricsCalculator) {
        this.result = taskResult;
        this.finishedAt = at;
        this.finishedNanos = System.nanoTime();
        partitions.stream().filter(p -> p.status() != PartitionStatus.COMPLETED)
                .forEach(p -> p.setStatus(taskResult.status() == TaskStatus.CANCELLED ? PartitionStatus.CANCELLED
                        : PartitionStatus.FAILED));
        completion.complete(new TaskOutcome(taskResult, snapshot(metricsCalculator)));
    }

    /**
     * Builds a {@code FAILED} or {@code CANCELLED} result for a task that ended before Core opened a session (for
     * example a validation error or no eligible worker).
     *
     * @param status terminal status
     * @param error why
     * @return Core's result document
     */
    public TaskResult resultWithoutSession(TaskStatus status, ErrorInfo error) {
        if (!lifecycle.current().isTerminal()) {
            lifecycle.transitionTo(status);
        }
        ErrorInfo located = error.status() != null ? error : new ErrorInfo(error.code(), error.message(),
                error.retryable(), TaskStatus.VALIDATING, error.partitionIndex(), error.violations());
        return new TaskResult(SchemaVersion.CURRENT, spec.taskId(), spec.experimentId(), spec.algorithm(),
                lifecycle.current(), ExecutionMode.DISTRIBUTED_CPU, 0, 0, null, null, null, ExecutionMetrics.NONE,
                EnvironmentInfo.capture(), lifecycle.history(), located);
    }

    /**
     * Current Core status.
     *
     * @return the status
     */
    public TaskStatus status() {
        if (result != null) {
            return result.status();
        }
        if (session == null) {
            return lifecycle.current();
        }
        TaskStatus core = session.status();
        // Every partition is in and Core's aggregation has been started on another thread.
        return completing && core == TaskStatus.RUNNING ? TaskStatus.AGGREGATING : core;
    }

    /**
     * Immutable view with current metrics.
     *
     * @param metricsCalculator computes the metrics
     * @return the snapshot
     */
    public TaskSnapshot snapshot(MetricsCalculator metricsCalculator) {
        int completed = (int) partitions.stream().filter(p -> p.status() == PartitionStatus.COMPLETED).count();
        int retries = (int) attemptsById.values().stream().filter(a -> a.number() > 1).count();
        return new TaskSnapshot(spec.taskId(), spec.experimentId(), spec.algorithm(), spec.constraints().mode(),
                status(), policy, submittedAt, finishedAt, partitions.size(), completed, attemptsById.size(),
                retries, result == null ? null : result.error(), metricsCalculator.compute(this),
                partitions.stream().map(PartitionState::snapshot).toList(), result, override);
    }

    /**
     * Core task specification.
     *
     * @return the specification
     */
    public TaskSpec spec() {
        return spec;
    }

    /**
     * Task id.
     *
     * @return the id
     */
    public String taskId() {
        return spec.taskId();
    }

    /**
     * Core session, once planned.
     *
     * @return the session, or {@code null}
     */
    public PartitionedTaskSession session() {
        return session;
    }

    /**
     * Requirements of one attempt, once planned.
     *
     * @return the requirements, or {@code null}
     */
    public TaskRequirements requirements() {
        return requirements;
    }

    /**
     * Partition states, once planned.
     *
     * @return the partitions in index order
     */
    public List<PartitionState> partitions() {
        return partitions;
    }

    /**
     * Scheduling policy of the task.
     *
     * @return the policy
     */
    public PolicyName policy() {
        return policy;
    }

    /**
     * Attempts allowed per partition.
     *
     * @return the limit
     */
    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * Time allowed per attempt.
     *
     * @return milliseconds
     */
    public long attemptTimeoutMillis() {
        return attemptTimeoutMillis;
    }

    /**
     * Workers that received attempts.
     *
     * @return worker ids in first-use order
     */
    public Set<String> workersUsed() {
        return Collections.unmodifiableSet(workersUsed);
    }

    /**
     * Workers this task may still use, under its worker limit.
     *
     * @return the allowed workers, or an empty set when any worker may be used
     */
    public Set<String> allowedWorkers() {
        return maxWorkers != null && workersUsed.size() >= maxWorkers ? workersUsed() : Set.of();
    }

    /**
     * Whether the task has ended.
     *
     * @return {@code true} once a result exists
     */
    public boolean isFinished() {
        return result != null;
    }

    /**
     * Whether aggregation is in progress.
     *
     * @return {@code true} while Core completes the task
     */
    public boolean isCompleting() {
        return completing;
    }

    /**
     * Marks aggregation as started.
     */
    public void startCompleting() {
        this.completing = true;
    }

    /**
     * Records a manual override of the recommended mode.
     *
     * @param value the override, or {@code null}
     */
    public void setOverride(StrategyOverride value) {
        this.override = value;
    }

    /**
     * The status last published as an event.
     *
     * @return the status, or {@code null} before the first event
     */
    public TaskStatus publishedStatus() {
        return publishedStatus;
    }

    /**
     * Records the status last published as an event.
     *
     * @param status the status
     */
    public void setPublishedStatus(TaskStatus status) {
        this.publishedStatus = status;
    }

    /**
     * Future completed when the task ends.
     *
     * @return the future
     */
    public CompletableFuture<TaskOutcome> completion() {
        return completion;
    }

    /**
     * Core's result, once ended.
     *
     * @return the result, or {@code null}
     */
    public TaskResult result() {
        return result;
    }

    /**
     * Submission time.
     *
     * @return monotonic time
     */
    public long submittedNanos() {
        return submittedNanos;
    }

    /**
     * Planning time.
     *
     * @return nanoseconds
     */
    public long planningNanos() {
        return planningNanos;
    }

    /**
     * Time of the first dispatch.
     *
     * @return monotonic time, or {@code -1}
     */
    public long firstDispatchNanos() {
        return firstDispatchNanos;
    }

    /**
     * Time of the last accepted result.
     *
     * @return monotonic time, or {@code -1}
     */
    public long lastAcceptedNanos() {
        return lastAcceptedNanos;
    }

    /**
     * Aggregation and verification time.
     *
     * @return nanoseconds
     */
    public long aggregationNanos() {
        return aggregationNanos;
    }

    /**
     * Scheduling time.
     *
     * @return nanoseconds
     */
    public long schedulingNanos() {
        return schedulingNanos;
    }

    /**
     * End time.
     *
     * @return monotonic time, or {@code -1}
     */
    public long finishedNanos() {
        return finishedNanos;
    }
}
