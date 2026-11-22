package org.districompute.distributed.coordinator;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.districompute.distributed.task.TaskOutcome;
import org.districompute.distributed.task.TaskSnapshot;

/** A submitted task: wait for its outcome, look at its progress, or cancel it. Thread-safe. */
public final class TaskHandle {

    private final String taskId;
    private final CompletableFuture<TaskOutcome> completion;
    private final Coordinator coordinator;

    TaskHandle(String taskId, CompletableFuture<TaskOutcome> completion, Coordinator coordinator) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.completion = completion;
        this.coordinator = coordinator;
    }

    /**
     * The task id.
     *
     * @return the id
     */
    public String taskId() {
        return taskId;
    }

    /**
     * Completes when the task ends (completed, failed or cancelled); never completes exceptionally.
     *
     * @return the future
     */
    public CompletableFuture<TaskOutcome> completion() {
        return completion.copy();
    }

    /**
     * Waits for the task to end.
     *
     * @param timeout how long to wait
     * @return Core's result and the runtime's final snapshot
     * @throws TimeoutException if the task is still running after the timeout
     * @throws InterruptedException if interrupted while waiting
     */
    public TaskOutcome await(Duration timeout) throws TimeoutException, InterruptedException {
        try {
            return completion.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Task futures never fail; this is a bug", e.getCause());
        }
    }

    /**
     * The task's current state.
     *
     * @return the snapshot
     */
    public TaskSnapshot snapshot() {
        return coordinator.task(taskId).orElseThrow();
    }

    /**
     * Requests cancellation.
     *
     * @return {@code true} if the task was running and is now cancelled
     */
    public boolean cancel() {
        return coordinator.cancel(taskId);
    }
}
