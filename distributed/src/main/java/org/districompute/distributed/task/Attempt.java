package org.districompute.distributed.task;

import java.time.Instant;
import java.util.Objects;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.scheduler.AssignmentRecord;

/**
 * Mutable state of one attempt. Owned by the coordinator's control loop; others read {@link #snapshot()}.
 * Durations combine the coordinator's clock (round trip) and the worker's clock (residence) without comparing
 * clocks of different machines.
 */
public final class Attempt {

    private final AttemptRef ref;
    private final int number;
    private final String workerId;
    private final AssignmentRecord assignment;
    private final long decidedNanos;
    private AttemptStatus status = AttemptStatus.DISPATCHING;
    private boolean slotHeld = true;
    private Instant dispatchedAt;
    private Instant acceptedAt;
    private Instant finishedAt;
    private long serializationNanos = -1;
    private long sentNanos = -1;
    private long resultNanos = -1;
    private long resultProcessingNanos = -1;
    private long bytesSent;
    private long bytesReceived;
    private WorkerTiming workerTiming;
    private long computeNanos = -1;
    private long progressUnits;
    private long progressTotal;
    private String progressUnit;
    private String failure;
    private boolean retryable;

    /**
     * Creates an attempt in state {@link AttemptStatus#DISPATCHING}.
     *
     * @param ref attempt ids
     * @param number attempt number
     * @param workerId chosen worker
     * @param assignment the scheduling decision
     * @param decidedNanos monotonic time of the decision
     */
    public Attempt(AttemptRef ref, int number, String workerId, AssignmentRecord assignment, long decidedNanos) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.number = number;
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        this.assignment = assignment;
        this.decidedNanos = decidedNanos;
    }

    /**
     * Records that the {@code TASK} frame was handed to the worker's connection.
     *
     * @param at wall-clock time
     * @param nanos monotonic time
     * @param serialization time to build and encode the work unit and the frame
     * @param frameBytes size of the frame
     */
    public void sent(Instant at, long nanos, long serialization, long frameBytes) {
        dispatchedAt = at;
        sentNanos = nanos;
        serializationNanos = serialization;
        bytesSent = frameBytes;
        if (status == AttemptStatus.DISPATCHING) {
            status = AttemptStatus.DISPATCHED;
        }
    }

    /**
     * Records {@code TASK_ACCEPTED}.
     *
     * @param at wall-clock time
     */
    public void accepted(Instant at) {
        acceptedAt = at;
        if (status == AttemptStatus.DISPATCHING || status == AttemptStatus.DISPATCHED) {
            status = AttemptStatus.ACCEPTED;
        }
    }

    /**
     * Records {@code TASK_PROGRESS}.
     *
     * @param completed units done
     * @param total total units (0 if unknown)
     * @param unit unit name
     */
    public void progress(long completed, long total, String unit) {
        progressUnits = completed;
        progressTotal = total;
        progressUnit = unit;
        if (!status.isTerminal()) {
            status = AttemptStatus.RUNNING;
        }
    }

    /**
     * Records the arrival of a {@code RESULT} frame.
     *
     * @param nanos monotonic arrival time
     * @param frameBytes size of the frame
     * @param timing durations measured by the worker
     * @param kernelNanos the kernel time reported by Core on the worker
     */
    public void resultReceived(long nanos, long frameBytes, WorkerTiming timing, long kernelNanos) {
        resultNanos = nanos;
        bytesReceived = frameBytes;
        workerTiming = timing;
        computeNanos = kernelNanos;
    }

    /**
     * Records the coordinator's time to validate and classify the result.
     *
     * @param nanos the duration
     */
    public void resultProcessed(long nanos) {
        resultProcessingNanos = nanos;
    }

    /**
     * Ends the attempt.
     *
     * @param terminal terminal status
     * @param at wall-clock time
     * @param reason failure reason, or {@code null}
     * @param canRetry whether the failure allows another attempt
     */
    public void finish(AttemptStatus terminal, Instant at, String reason, boolean canRetry) {
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException(terminal + " is not terminal");
        }
        status = terminal;
        finishedAt = at;
        failure = reason;
        retryable = canRetry;
    }

    /**
     * Marks the worker's slot as free again.
     *
     * @return {@code true} if the slot was held until now
     */
    public boolean releaseSlot() {
        boolean held = slotHeld;
        slotHeld = false;
        return held;
    }

    /**
     * Transport time: the coordinator's round trip minus the worker's residence.
     *
     * @return nanoseconds, or {@code -1} if unknown
     */
    public long transferNanos() {
        if (sentNanos < 0 || resultNanos < 0 || workerTiming == null) {
            return -1;
        }
        return Math.max(0, resultNanos - sentNanos - workerTiming.residenceNanos());
    }

    /**
     * Round trip measured by the coordinator.
     *
     * @return nanoseconds from handing the frame to the connection to receiving the result, or {@code -1}
     */
    public long roundTripNanos() {
        return sentNanos < 0 || resultNanos < 0 ? -1 : resultNanos - sentNanos;
    }

    /**
     * Immutable view.
     *
     * @return the snapshot
     */
    public AttemptSnapshot snapshot() {
        return new AttemptSnapshot(ref.attemptId(), number, workerId, status, assignment, dispatchedAt, acceptedAt,
                finishedAt, serializationNanos, roundTripNanos(), transferNanos(),
                workerTiming == null ? -1 : workerTiming.queueNanos(),
                workerTiming == null ? -1 : workerTiming.executeNanos(), computeNanos, resultProcessingNanos,
                bytesSent, bytesReceived, progressUnits, progressTotal, progressUnit, failure, retryable);
    }

    /**
     * Attempt ids.
     *
     * @return the ids
     */
    public AttemptRef ref() {
        return ref;
    }

    /**
     * Attempt number.
     *
     * @return the number, starting at 1
     */
    public int number() {
        return number;
    }

    /**
     * The worker the attempt was sent to.
     *
     * @return the worker id
     */
    public String workerId() {
        return workerId;
    }

    /**
     * Current status.
     *
     * @return the status
     */
    public AttemptStatus status() {
        return status;
    }

    /**
     * Whether the attempt still occupies a slot on its worker.
     *
     * @return {@code true} until released
     */
    public boolean slotHeld() {
        return slotHeld;
    }

    /**
     * Monotonic time of the scheduling decision.
     *
     * @return {@link System#nanoTime()} value
     */
    public long decidedNanos() {
        return decidedNanos;
    }

    /**
     * Monotonic time when the frame was handed to the connection.
     *
     * @return {@link System#nanoTime()} value, or {@code -1} before
     */
    public long sentNanos() {
        return sentNanos;
    }

    /**
     * Serialization time on the coordinator.
     *
     * @return nanoseconds, or {@code -1}
     */
    public long serializationNanos() {
        return serializationNanos;
    }

    /**
     * Result processing time on the coordinator.
     *
     * @return nanoseconds, or {@code -1}
     */
    public long resultProcessingNanos() {
        return resultProcessingNanos;
    }

    /**
     * Worker timings from the result.
     *
     * @return the timings, or {@code null}
     */
    public WorkerTiming workerTiming() {
        return workerTiming;
    }

    /**
     * Kernel time reported by Core on the worker.
     *
     * @return nanoseconds, or {@code -1}
     */
    public long computeNanos() {
        return computeNanos;
    }

    /**
     * Bytes of the {@code TASK} frame.
     *
     * @return bytes
     */
    public long bytesSent() {
        return bytesSent;
    }

    /**
     * Bytes of the {@code RESULT} frame.
     *
     * @return bytes
     */
    public long bytesReceived() {
        return bytesReceived;
    }

    /**
     * The scheduling decision.
     *
     * @return the record
     */
    public AssignmentRecord assignment() {
        return assignment;
    }
}
