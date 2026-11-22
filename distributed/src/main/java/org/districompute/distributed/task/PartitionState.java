package org.districompute.distributed.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.districompute.core.partition.PartitionDescriptor;
import org.districompute.core.partition.PartitionStatus;

/** Mutable dispatch state of one partition. Owned by the coordinator's control loop. */
public final class PartitionState {

    private final PartitionDescriptor descriptor;
    private final List<Attempt> attempts = new ArrayList<>();
    private final Set<String> excludedWorkers = new LinkedHashSet<>();
    private PartitionStatus status = PartitionStatus.PENDING;
    private boolean queued;
    private int countedFailures;
    private int rejections;

    /**
     * Creates the state of a planned partition.
     *
     * @param descriptor Core's descriptor
     */
    public PartitionState(PartitionDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Adds a new attempt.
     *
     * @param attempt the attempt
     */
    public void add(Attempt attempt) {
        attempts.add(attempt);
        status = PartitionStatus.RUNNING;
    }

    /**
     * The next attempt number.
     *
     * @return number of attempts so far plus one
     */
    public int nextAttemptNumber() {
        return attempts.size() + 1;
    }

    /**
     * Attempts that have not ended.
     *
     * @return the live attempts
     */
    public List<Attempt> live() {
        return attempts.stream().filter(a -> !a.status().isTerminal()).toList();
    }

    /**
     * Records a failure that counts against the retry budget, and excludes the worker for later attempts.
     *
     * @param workerId the worker the attempt failed on
     */
    public void countFailure(String workerId) {
        countedFailures++;
        excludedWorkers.add(workerId);
    }

    /**
     * Records a refusal by a worker (does not use up the retry budget).
     *
     * @param workerId the refusing worker
     * @param excludeWorker whether the worker can never run this partition
     */
    public void countRejection(String workerId, boolean excludeWorker) {
        rejections++;
        if (excludeWorker) {
            excludedWorkers.add(workerId);
        }
    }

    /**
     * Failures counted against the retry budget.
     *
     * @return the count
     */
    public int countedFailures() {
        return countedFailures;
    }

    /**
     * Refusals so far.
     *
     * @return the count
     */
    public int rejections() {
        return rejections;
    }

    /**
     * Workers to avoid for the next attempt.
     *
     * @return the worker ids
     */
    public Set<String> excludedWorkers() {
        return Collections.unmodifiableSet(excludedWorkers);
    }

    /**
     * Core's descriptor.
     *
     * @return the descriptor
     */
    public PartitionDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Partition index.
     *
     * @return the index
     */
    public int index() {
        return descriptor.index();
    }

    /**
     * Current status (Core's partition status).
     *
     * @return the status
     */
    public PartitionStatus status() {
        return status;
    }

    /**
     * Sets the status.
     *
     * @param value the status
     */
    public void setStatus(PartitionStatus value) {
        this.status = value;
    }

    /**
     * Whether the partition waits in the pending queue.
     *
     * @return {@code true} if queued
     */
    public boolean queued() {
        return queued;
    }

    /**
     * Marks the partition as queued or not.
     *
     * @param value whether it is queued
     */
    public void setQueued(boolean value) {
        this.queued = value;
    }

    /**
     * All attempts, oldest first.
     *
     * @return the attempts
     */
    public List<Attempt> attempts() {
        return Collections.unmodifiableList(attempts);
    }

    /**
     * Immutable view.
     *
     * @return the snapshot
     */
    public PartitionSnapshot snapshot() {
        return new PartitionSnapshot(descriptor.index(), descriptor.partitionId(), status,
                attempts.stream().map(Attempt::snapshot).toList());
    }
}
