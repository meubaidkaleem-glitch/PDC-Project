package org.districompute.distributed.coordinator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.CancellationSource;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.execution.ExecutionOutcome;
import org.districompute.core.execution.LocalExecutionEngine;
import org.districompute.core.execution.ProgressEvent;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.strategy.StrategyOverride;
import org.districompute.distributed.task.ResultSink;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.task.TaskSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code SEQUENTIAL_CPU} and {@code MULTITHREADED_CPU} tasks on the coordinator's machine through Core's
 * {@link LocalExecutionEngine}, one at a time, with the same task snapshots and events as distributed tasks. State
 * changes run on the control loop.
 */
final class LocalTaskRunner implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalTaskRunner.class);

    /** One local task. */
    private static final class LocalTask {
        private final TaskSpec spec;
        private final StrategyOverride override;
        private final ResultSink sink;
        private final Instant submittedAt;
        private final CancellationSource cancellation = new CancellationSource();
        private final CompletableFuture<TaskOutcome> completion = new CompletableFuture<>();
        private TaskStatus status = TaskStatus.CREATED;
        private int completedPartitions;
        private int totalPartitions;
        private TaskResult result;
        private Instant finishedAt;

        LocalTask(TaskSpec spec, StrategyOverride override, ResultSink sink, Instant submittedAt) {
            this.spec = spec;
            this.override = override;
            this.sink = sink;
            this.submittedAt = submittedAt;
        }

        TaskSnapshot snapshot() {
            return new TaskSnapshot(spec.taskId(), spec.experimentId(), spec.algorithm(), spec.constraints().mode(),
                    status, null, submittedAt, finishedAt, result != null ? result.partitions() : totalPartitions,
                    completedPartitions, 0, 0, result == null ? null : result.error(), null, List.of(), result,
                    override);
        }
    }

    private final CoordinatorConfig config;
    private final ControlLoop loop;
    private final RuntimeEvents events;
    private final StrategyService strategy;
    private final LocalExecutionEngine engine;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
            Thread.ofPlatform().name("coordinator-local").daemon(true).unstarted(runnable));
    private final Map<String, LocalTask> tasks = new LinkedHashMap<>();

    LocalTaskRunner(CoordinatorConfig config, ControlLoop loop, RuntimeEvents events, StrategyService strategy) {
        this.config = config;
        this.loop = loop;
        this.events = events;
        this.strategy = strategy;
        this.engine = new LocalExecutionEngine(config.registry(),
                org.districompute.core.execution.EngineConfig.defaults());
    }

    boolean contains(String taskId) {
        return tasks.containsKey(taskId);
    }

    CompletableFuture<TaskOutcome> submit(TaskSpec spec, StrategyOverride override, ResultSink sink) {
        LocalTask task = new LocalTask(spec, override, sink, config.clock().instant());
        tasks.put(spec.taskId(), task);
        trim();
        events.localTaskSubmitted(spec, override);
        status(task, TaskStatus.CREATED);
        executor.execute(() -> run(task));
        return task.completion;
    }

    boolean cancel(String taskId) {
        LocalTask task = tasks.get(taskId);
        if (task == null || task.result != null) {
            return false;
        }
        return task.cancellation.cancel(CancellationReason.USER_REQUESTED);
    }

    Optional<TaskSnapshot> snapshot(String taskId) {
        return Optional.ofNullable(tasks.get(taskId)).map(LocalTask::snapshot);
    }

    List<TaskSnapshot> snapshots() {
        return tasks.values().stream().map(LocalTask::snapshot).toList();
    }

    int active() {
        return (int) tasks.values().stream().filter(t -> t.result == null).count();
    }

    private void run(LocalTask task) {
        ExecutionOutcome outcome;
        try {
            outcome = engine.execute(task.spec, task.cancellation,
                    event -> loop.execute(() -> progress(task, event)));
        } catch (RuntimeException e) {
            LOG.error("Local task {} failed unexpectedly", task.spec.taskId(), e);
            outcome = null;
        }
        TaskResult result = outcome == null ? null : outcome.taskResult();
        if (result != null && result.status() == TaskStatus.COMPLETED && task.sink != ResultSink.NONE
                && outcome.value() != null) {
            // On this executor thread, before the completion future completes; the value is not kept.
            try {
                task.sink.accept(result, outcome.value());
            } catch (Exception e) {
                LOG.warn("The result sink of task {} failed; the task is still COMPLETED", task.spec.taskId(), e);
            }
        }
        loop.execute(() -> finish(task, result));
    }

    private void progress(LocalTask task, ProgressEvent event) {
        if (task.result != null) {
            return;
        }
        status(task, event.status());
        if (event.totalPartitions() > 0 && event.completedPartitions() != task.completedPartitions) {
            task.completedPartitions = event.completedPartitions();
            task.totalPartitions = event.totalPartitions();
            events.localTaskProgress(task.spec, task.completedPartitions, task.totalPartitions);
        }
    }

    private void finish(LocalTask task, TaskResult result) {
        if (result == null) {
            task.completion.completeExceptionally(new IllegalStateException("Core's engine failed unexpectedly"));
            return;
        }
        status(task, result.status());
        task.result = result;
        task.finishedAt = config.clock().instant();
        task.completedPartitions = result.status() == TaskStatus.COMPLETED ? result.partitions()
                : task.completedPartitions;
        if (result.status() == TaskStatus.COMPLETED && result.profile() != null
                && result.metrics().executionNanos() > 0) {
            strategy.recordMeasured(new org.districompute.core.scheduling.Calibration(result.algorithm().id(),
                    result.mode(), Math.max(1, result.threads()),
                    result.profile().estimatedOperations() / (result.metrics().executionNanos() / 1e9),
                    result.profile().estimatedOperations()));
        }
        events.localTaskFinished(task.spec, result);
        task.completion.complete(new TaskOutcome(result, task.snapshot()));
    }

    private void status(LocalTask task, TaskStatus status) {
        if (status != task.status || status == TaskStatus.CREATED) {
            TaskStatus before = task.status == status ? null : task.status;
            task.status = status;
            events.localTaskStatus(task.spec, before, status);
        }
    }

    private void trim() {
        int retained = config.tasks().retainedTasks();
        List<String> finished = new ArrayList<>();
        tasks.forEach((id, t) -> {
            if (t.result != null) {
                finished.add(id);
            }
        });
        for (int i = 0; i < finished.size() - retained; i++) {
            tasks.remove(finished.get(i));
        }
    }

    static boolean handles(ExecutionMode mode) {
        return mode == ExecutionMode.SEQUENTIAL_CPU || mode == ExecutionMode.MULTITHREADED_CPU;
    }

    @Override
    public void close() {
        tasks.values().forEach(t -> t.cancellation.cancel(CancellationReason.SHUTDOWN));
        executor.shutdownNow();
        engine.close();
    }
}
