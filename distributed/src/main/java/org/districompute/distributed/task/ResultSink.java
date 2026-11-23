package org.districompute.distributed.task;

import org.districompute.core.result.TaskResult;

/**
 * Receives the full value of a completed task, for callers that store or present results (the Application).
 *
 * <p>Core's {@link TaskResult} carries a summary and a digest of the result. The value itself (for example the
 * product matrix) is available only in the process that ran Core's aggregation. The coordinator calls the sink
 * <b>once</b>, only for a task that {@code COMPLETED} (so Core has verified the value), on one of its own threads,
 * and <b>before</b> the task's completion future completes. It does not keep the value afterwards.
 *
 * <p>An exception thrown by the sink is logged. It does not change the task's outcome: the computation succeeded,
 * and storing the value is the caller's concern.
 */
@FunctionalInterface
public interface ResultSink {

    /** A sink that ignores values. */
    ResultSink NONE = (result, value) -> { };

    /**
     * Receives a completed task's value.
     *
     * @param result Core's verified result (status {@code COMPLETED})
     * @param value the full value: the object Core's algorithm produced (for example {@code Matrix},
     *     {@code long[]}, {@code double[]})
     * @throws Exception if the caller cannot handle the value
     */
    void accept(TaskResult result, Object value) throws Exception;
}
