package org.districompute.distributed.simulation;

import java.util.Objects;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Applies a {@link FaultProfile} at the worker's hook points. Each attempt's behaviour is drawn once, from the
 * profile's seed and the attempt id, so repeating an experiment repeats the same faults for the same attempts.
 */
public final class FaultInjector {

    /** What an attempt does under fault simulation. */
    public enum Behaviour {
        /** Runs normally (apart from delays and slowdown). */
        NORMAL,
        /** Never answers, not even to {@code CANCEL}. */
        HANG,
        /** Reports a retryable kernel failure. */
        FAIL,
        /** Sends a corrupted result. */
        CORRUPT,
        /** Sends its result twice. */
        DUPLICATE
    }

    private final FaultProfile profile;
    private final AtomicInteger tasksReceived = new AtomicInteger();

    /**
     * Creates an injector.
     *
     * @param profile the profile ({@link FaultProfile#NONE} disables everything)
     */
    public FaultInjector(FaultProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    /**
     * The profile.
     *
     * @return the profile
     */
    public FaultProfile profile() {
        return profile;
    }

    /**
     * Whether any fault simulation is active.
     *
     * @return {@code true} if enabled
     */
    public boolean enabled() {
        return profile.enabled();
    }

    /**
     * The behaviour of an attempt (deterministic for a seed and attempt id).
     *
     * @param attemptId the attempt
     * @return the behaviour
     */
    public Behaviour behaviour(String attemptId) {
        if (!profile.enabled()) {
            return Behaviour.NORMAL;
        }
        double roll = new SplittableRandom(profile.seed() * 31 + attemptId.hashCode()).nextDouble();
        double threshold = profile.hangProbability();
        if (roll < threshold) {
            return Behaviour.HANG;
        }
        threshold += profile.failProbability();
        if (roll < threshold) {
            return Behaviour.FAIL;
        }
        threshold += profile.corruptProbability();
        if (roll < threshold) {
            return Behaviour.CORRUPT;
        }
        threshold += profile.duplicateProbability();
        return roll < threshold ? Behaviour.DUPLICATE : Behaviour.NORMAL;
    }

    /**
     * Counts a received task.
     *
     * @return {@code true} if the connection must now be dropped
     */
    public boolean disconnectNow() {
        int received = tasksReceived.incrementAndGet();
        return profile.enabled() && profile.disconnectAfterTasks() > 0 && received == profile.disconnectAfterTasks();
    }

    /**
     * Whether heartbeats are suppressed.
     *
     * @param millisSinceRegistration time since the current registration
     * @return {@code true} if no heartbeat may be sent
     */
    public boolean heartbeatsStopped(long millisSinceRegistration) {
        return profile.enabled() && profile.stopHeartbeatsAfterMillis() > 0
                && millisSinceRegistration >= profile.stopHeartbeatsAfterMillis();
    }

    /**
     * Extra kernel time to add, simulating a slower machine.
     *
     * @param kernelNanos the real kernel time
     * @return nanoseconds to wait in addition
     */
    public long extraKernelNanos(long kernelNanos) {
        return profile.enabled() ? (long) (kernelNanos * (profile.slowdownFactor() - 1.0)) : 0;
    }

    /**
     * Delay before a result is sent.
     *
     * @return milliseconds
     */
    public long responseDelayMillis() {
        return profile.enabled() ? profile.responseDelayMillis() : 0;
    }

    /**
     * Delay added to every outgoing message.
     *
     * @return milliseconds
     */
    public long messageLatencyMillis() {
        return profile.enabled() ? profile.messageLatencyMillis() : 0;
    }
}
