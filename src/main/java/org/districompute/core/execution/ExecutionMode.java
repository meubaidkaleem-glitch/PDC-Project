package org.districompute.core.execution;

/**
 * Execution strategies known to the platform (shared rule 4).
 *
 * <p>Knowing a mode does not mean it is available. Availability is reported per algorithm by the registry
 * ({@code AlgorithmCapabilities}) and, for remote resources, by the distributed runtime. Core's local engine runs
 * only {@link #SEQUENTIAL_CPU} and {@link #MULTITHREADED_CPU}.
 */
public enum ExecutionMode {
    /** A single thread on the local node; the reference baseline for speedup. */
    SEQUENTIAL_CPU(true),
    /** Shared-memory parallel execution on the local node using a bounded worker pool. */
    MULTITHREADED_CPU(true),
    /** Partitions are executed by remote workers of the distributed runtime (DistriCompute-Distributed). */
    DISTRIBUTED_CPU(false),
    /** Execution on a GPU through a detected GPU backend. */
    GPU(false),
    /** A combination of CPU, GPU and distributed resources. */
    HYBRID(false);

    private final boolean local;

    ExecutionMode(boolean local) {
        this.local = local;
    }

    /**
     * Whether Core's local engine can execute this mode on its own.
     *
     * @return {@code true} for the local CPU modes
     */
    public boolean isLocal() {
        return local;
    }
}
