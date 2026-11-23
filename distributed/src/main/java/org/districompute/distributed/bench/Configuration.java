package org.districompute.distributed.bench;

import java.util.Objects;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.distributed.scheduler.PolicyName;

/**
 * One measured setting: a workload, an execution mode and, for distributed runs, the workers and scheduling.
 *
 * @param label name shown in the report, unique within the experiment
 * @param workload the task
 * @param mode {@code SEQUENTIAL_CPU}, {@code MULTITHREADED_CPU} or {@code DISTRIBUTED_CPU}
 * @param threads threads of a multithreaded run (1 otherwise)
 * @param workers normal worker processes of a distributed run
 * @param slotsPerWorker concurrent attempts per worker process
 * @param slowWorkers additional worker processes started with a slowdown fault profile
 * @param slowdown kernel slowdown factor of the slow workers
 * @param policy scheduling policy ({@code null}: the coordinator default)
 * @param partitionsPerSlot partitions requested per free slot ({@code 0}: the default)
 */
public record Configuration(String label, Workload workload, ExecutionMode mode, int threads, int workers,
        int slotsPerWorker, int slowWorkers, double slowdown, PolicyName policy, int partitionsPerSlot) {

    /** Validates the components. */
    public Configuration {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(workload, "workload");
        Objects.requireNonNull(mode, "mode");
        if (mode == ExecutionMode.DISTRIBUTED_CPU && (workers + slowWorkers < 1 || slotsPerWorker < 1)) {
            throw new IllegalArgumentException("A distributed configuration needs workers with slots");
        }
        if (slowWorkers > 0 && slowdown < 1) {
            throw new IllegalArgumentException("slowdown must be at least 1");
        }
    }

    /**
     * Core's sequential reference run on the benchmark's machine.
     *
     * @param workload the task
     * @return the configuration
     */
    public static Configuration sequential(Workload workload) {
        return new Configuration("sequential", workload, ExecutionMode.SEQUENTIAL_CPU, 1, 0, 0, 0, 1, null, 0);
    }

    /**
     * Core's multithreaded run on the benchmark's machine.
     *
     * @param workload the task
     * @param threads threads
     * @return the configuration
     */
    public static Configuration multithreaded(Workload workload, int threads) {
        return new Configuration("multithreaded " + threads + "t", workload, ExecutionMode.MULTITHREADED_CPU,
                threads, 0, 0, 0, 1, null, 0);
    }

    /**
     * A distributed run.
     *
     * @param workload the task
     * @param workers worker processes
     * @param slotsPerWorker slots per worker
     * @return the configuration
     */
    public static Configuration distributed(Workload workload, int workers, int slotsPerWorker) {
        return new Configuration("distributed " + workers + "w x " + slotsPerWorker + "s", workload,
                ExecutionMode.DISTRIBUTED_CPU, 1, workers, slotsPerWorker, 0, 1, null, 0);
    }

    /**
     * Same configuration with another label.
     *
     * @param value the label
     * @return the copy
     */
    public Configuration withLabel(String value) {
        return new Configuration(value, workload, mode, threads, workers, slotsPerWorker, slowWorkers, slowdown,
                policy, partitionsPerSlot);
    }

    /**
     * Same configuration with a scheduling policy.
     *
     * @param value the policy
     * @return the copy
     */
    public Configuration withPolicy(PolicyName value) {
        return new Configuration(label, workload, mode, threads, workers, slotsPerWorker, slowWorkers, slowdown,
                value, partitionsPerSlot);
    }

    /**
     * Same configuration with a partition count per slot.
     *
     * @param value partitions per slot
     * @return the copy
     */
    public Configuration withPartitionsPerSlot(int value) {
        return new Configuration(label, workload, mode, threads, workers, slotsPerWorker, slowWorkers, slowdown,
                policy, value);
    }

    /**
     * Same configuration with additional slow workers.
     *
     * @param count slow worker processes
     * @param factor kernel slowdown factor
     * @return the copy
     */
    public Configuration withSlowWorkers(int count, double factor) {
        return new Configuration(label, workload, mode, threads, workers, slotsPerWorker, count, factor, policy,
                partitionsPerSlot);
    }

    /**
     * Processor cores the configuration may use for computation.
     *
     * @return threads of a local run, or the slots of all workers
     */
    public int cores() {
        return mode == ExecutionMode.DISTRIBUTED_CPU ? (workers + slowWorkers) * slotsPerWorker : threads;
    }
}
