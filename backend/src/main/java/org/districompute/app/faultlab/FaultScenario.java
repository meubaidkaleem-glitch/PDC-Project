package org.districompute.app.faultlab;

import java.time.Duration;
import java.util.List;
import org.districompute.distributed.simulation.FaultProfile;

/**
 * The predefined fault scenarios of the fault lab ({@code docs/fault-lab.md}). Each one gives the faulty worker a
 * DistriCompute-Distributed {@link FaultProfile}; the other workers are healthy. The expected detection and recovery
 * describe what the runtime is designed to do; each run records what actually happened.
 */
public enum FaultScenario {

    /** The worker drops its connection after receiving its first task. */
    WORKER_DISCONNECT("Worker disconnects", "The faulty worker drops its connection right after it receives its first "
            + "partition, then reconnects like after a network failure.",
            "The coordinator sees the connection close and marks the worker's attempts as lost.",
            "Lost partitions are retried on connected workers; the task completes with the same result.",
            Duration.ofSeconds(20), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 300, 0, 1.0, 1, 0, 0, 0, 0, 0, List.of());
        }
    },
    /** The worker stops sending heartbeats while it keeps its partitions. */
    MISSED_HEARTBEATS("Missed heartbeats", "The faulty worker stops sending heartbeats 300 ms after it registers and "
            + "answers its partitions only after 4 s.",
            "Without heartbeats the worker becomes suspect, then lost when the heartbeat timeout (2 s in the lab) "
                    + "expires.",
            "Its partitions are retried elsewhere; results it sends later are ignored.",
            Duration.ofSeconds(20), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 4000, 0, 1.0, 0, 300, 0, 0, 0, 0, List.of());
        }
    },
    /** Every result is corrupted in transit. */
    CORRUPTED_RESULTS("Corrupted results", "Every result of the faulty worker is altered in transit, so its checksum "
            + "no longer matches.",
            "The coordinator verifies each partial result and rejects the corrupted ones.",
            "Rejected partitions are retried on other workers; no corrupted value reaches the result.",
            Duration.ofSeconds(20), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 0, 1.0, 0, 0, 0, 0, 1.0, 0, List.of());
        }
    },
    /** Every result is sent twice. */
    DUPLICATE_RESULTS("Duplicate results", "The faulty worker sends every result twice.",
            "The coordinator recognises the second copy of a result it already accepted.",
            "Duplicates are ignored; no partition is counted twice.",
            Duration.ofSeconds(20), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 0, 1.0, 0, 0, 0, 0, 0, 1.0, List.of());
        }
    },
    /**
     * Every attempt fails with a retryable kernel error. (With a probability below 1 the round-robin lab task can
     * give the faulty worker attempts that all succeed, and the run would show nothing.)
     */
    KERNEL_FAILURES("Kernel failures", "Every attempt on the faulty worker reports a (retryable) kernel failure.",
            "Each failure is reported by the worker and recorded with its reason.",
            "Failed partitions are retried on workers that have not failed them, up to six attempts each.",
            Duration.ofSeconds(20), 6) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 0, 1.0, 0, 0, 0, 1.0, 0, 0, List.of());
        }
    },
    /** Attempts never answer. */
    HANGING_ATTEMPTS("Hanging attempts", "Every attempt on the faulty worker hangs: it never answers, not even to a "
            + "cancellation.",
            "Each attempt times out after 2 s (the lab's attempt timeout).",
            "Timed-out partitions are retried on other workers.",
            Duration.ofSeconds(2), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 0, 1.0, 0, 0, 1.0, 0, 0, 0, List.of());
        }
    },
    /** The worker computes five times slower. */
    SLOW_WORKER("Slow worker", "The faulty worker computes five times slower than the others (a straggler).",
            "Nothing fails; the slowdown shows as load imbalance and in the per-worker times.",
            "The task completes; how much the straggler delays it depends on the scheduling policy.",
            Duration.ofSeconds(60), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 0, 5.0, 0, 0, 0, 0, 0, 0, List.of());
        }
    },
    /** Every message of the worker is delayed. */
    SLOW_NETWORK("Slow network link", "Every message the faulty worker sends is delayed by 150 ms.",
            "Nothing fails; the delay shows in the transfer time and the communication share.",
            "The task completes with the same result, later.",
            Duration.ofSeconds(20), 4) {
        @Override
        FaultProfile profile(long seed) {
            return new FaultProfile(true, seed, 0, 150, 1.0, 0, 0, 0, 0, 0, 0, List.of());
        }
    };

    private final String title;
    private final String fault;
    private final String expectedDetection;
    private final String expectedRecovery;
    private final Duration attemptTimeout;
    private final int maxAttempts;

    FaultScenario(String title, String fault, String expectedDetection, String expectedRecovery,
            Duration attemptTimeout, int maxAttempts) {
        this.title = title;
        this.fault = fault;
        this.expectedDetection = expectedDetection;
        this.expectedRecovery = expectedRecovery;
        this.attemptTimeout = attemptTimeout;
        this.maxAttempts = maxAttempts;
    }

    /**
     * The faulty worker's profile.
     *
     * @param seed seed of the random choices
     * @return the profile
     */
    abstract FaultProfile profile(long seed);

    /** @return short name */
    public String title() {
        return title;
    }

    /** @return the injected fault */
    public String fault() {
        return fault;
    }

    /** @return how the runtime is designed to detect it */
    public String expectedDetection() {
        return expectedDetection;
    }

    /** @return how the runtime is designed to recover */
    public String expectedRecovery() {
        return expectedRecovery;
    }

    /** @return the attempt timeout used in the lab for this scenario */
    public Duration attemptTimeout() {
        return attemptTimeout;
    }

    /** @return attempts allowed per partition */
    public int maxAttempts() {
        return maxAttempts;
    }
}
