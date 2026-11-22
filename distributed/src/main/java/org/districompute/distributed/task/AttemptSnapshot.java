package org.districompute.distributed.task;

import java.time.Instant;
import org.districompute.distributed.scheduler.AssignmentRecord;

/**
 * Immutable view of an attempt. Durations are nanoseconds; {@code -1} means not (yet) measured.
 *
 * @param attemptId attempt id
 * @param attempt attempt number
 * @param workerId worker the attempt was sent to
 * @param status dispatch status
 * @param assignment the scheduling decision that chose the worker
 * @param dispatchedAt when the {@code TASK} frame was handed to the connection
 * @param acceptedAt when {@code TASK_ACCEPTED} arrived
 * @param finishedAt when the attempt ended
 * @param serializationNanos coordinator: building and encoding the work unit and the frame
 * @param roundTripNanos coordinator: frame handed to the connection until the result frame arrived
 * @param transferNanos round trip minus the worker's residence: time in transport, both directions
 * @param workerQueueNanos worker: admission to start of execution
 * @param workerExecuteNanos worker: decode, kernel and encode
 * @param computeNanos worker: the kernel only (Core)
 * @param resultProcessingNanos coordinator: decoding, validating and classifying the result
 * @param bytesSent size of the {@code TASK} frame
 * @param bytesReceived size of the {@code RESULT} frame
 * @param progressUnits latest progress reported
 * @param progressTotal total units (0 if unknown)
 * @param progressUnit unit of the progress values
 * @param failure why the attempt failed, or {@code null}
 * @param retryable whether the failure allowed another attempt
 */
public record AttemptSnapshot(String attemptId, int attempt, String workerId, AttemptStatus status,
        AssignmentRecord assignment, Instant dispatchedAt, Instant acceptedAt, Instant finishedAt,
        long serializationNanos, long roundTripNanos, long transferNanos, long workerQueueNanos,
        long workerExecuteNanos, long computeNanos, long resultProcessingNanos, long bytesSent, long bytesReceived,
        long progressUnits, long progressTotal, String progressUnit, String failure, boolean retryable) {
}
