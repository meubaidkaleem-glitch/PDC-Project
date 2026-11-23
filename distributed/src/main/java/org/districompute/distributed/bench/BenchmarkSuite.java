package org.districompute.distributed.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.districompute.distributed.scheduler.PolicyName;

/**
 * The benchmark suites. {@code standard} produces the measurements in {@code docs/benchmarks.md}; {@code quick}
 * runs the same code paths on small inputs (used by the {@code performance} test group, which checks correctness,
 * never speed).
 */
public final class BenchmarkSuite {

    private BenchmarkSuite() {
    }

    /**
     * A suite by name.
     *
     * @param name {@code standard} or {@code quick}
     * @return the experiments
     */
    public static List<Experiment> named(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "standard" -> standard();
            case "quick" -> quick();
            default -> throw new IllegalArgumentException("Unknown suite " + name + "; use standard or quick");
        };
    }

    /**
     * The full suite (about 15 minutes on a 4-core machine with the default 5 measured runs).
     *
     * @return the experiments
     */
    public static List<Experiment> standard() {
        Workload primes = Workload.primes(10_000_000_000L);
        List<Experiment> experiments = new ArrayList<>();

        experiments.add(new Experiment("scaling", "Scaling with worker processes",
                "How does a compute-bound task with tiny messages scale from 1 to 4 single-slot worker processes, "
                        + "compared with Core's sequential and multithreaded runs on the same machine?",
                List.of(Configuration.sequential(primes), Configuration.multithreaded(primes, 4),
                        Configuration.distributed(primes, 1, 1), Configuration.distributed(primes, 2, 1),
                        Configuration.distributed(primes, 3, 1), Configuration.distributed(primes, 4, 1))));

        List<Configuration> granularity = new ArrayList<>();
        for (int perSlot : new int[] {1, 2, 4, 8, 16, 32}) {
            granularity.add(Configuration.distributed(primes, 2, 2).withPartitionsPerSlot(perSlot)
                    .withLabel((perSlot * 4) + " partitions (" + perSlot + " per slot)"));
        }
        experiments.add(new Experiment("granularity", "Partition granularity",
                "What do more, smaller partitions cost and gain on 2 workers with 2 slots each?", granularity));

        List<Configuration> sizes = new ArrayList<>();
        for (long bound : new long[] {1_000_000L, 10_000_000L, 100_000_000L, 1_000_000_000L}) {
            Workload w = Workload.primes(bound);
            String n = Workload.compact(bound);
            sizes.add(Configuration.sequential(w).withLabel("sequential, n=" + n));
            sizes.add(Configuration.multithreaded(w, 4).withLabel("multithreaded 4t, n=" + n));
            sizes.add(Configuration.distributed(w, 2, 2).withLabel("distributed 2w x 2s, n=" + n));
        }
        experiments.add(new Experiment("size", "Workload size",
                "From which problem size does distribution pay for its fixed costs?", sizes));

        List<Configuration> communication = new ArrayList<>();
        for (int n : new int[] {256, 512, 1024, 1536}) {
            Workload w = Workload.matrix(n);
            communication.add(Configuration.sequential(w).withLabel("sequential, n=" + n));
            communication.add(Configuration.multithreaded(w, 4).withLabel("multithreaded 4t, n=" + n));
            communication.add(Configuration.distributed(w, 2, 2).withLabel("distributed 2w x 2s, n=" + n));
        }
        experiments.add(new Experiment("communication", "Communication-heavy work",
                "How much of a matrix multiplication's distributed time goes to moving and encoding data?",
                communication));

        List<Configuration> imbalance = new ArrayList<>();
        imbalance.add(Configuration.distributed(primes, 2, 1).withPolicy(PolicyName.DYNAMIC)
                .withPartitionsPerSlot(4).withLabel("2 normal workers only (DYNAMIC)"));
        for (PolicyName policy : PolicyName.values()) {
            imbalance.add(Configuration.distributed(primes, 2, 1).withSlowWorkers(1, 4.0).withPolicy(policy)
                    .withPartitionsPerSlot(4).withLabel("2 normal + 1 slow (4x), " + policy));
        }
        experiments.add(new Experiment("imbalance", "Load imbalance and scheduling policies",
                "How do the policies cope when one of three workers is four times slower?", imbalance));
        return List.copyOf(experiments);
    }

    /**
     * Small inputs, the same code paths: local runs, several worker processes, a slow worker, data-carrying work
     * units.
     *
     * @return the experiments
     */
    public static List<Experiment> quick() {
        Workload primes = Workload.primes(5_000_000L);
        Workload matrix = Workload.matrix(96);
        return List.of(
                new Experiment("scaling", "Scaling (quick)", "Smoke test of local and distributed runs",
                        List.of(Configuration.sequential(primes), Configuration.multithreaded(primes, 2),
                                Configuration.distributed(primes, 2, 1))),
                new Experiment("communication", "Communication (quick)", "Smoke test of data-carrying work units",
                        List.of(Configuration.sequential(matrix), Configuration.distributed(matrix, 1, 2))),
                new Experiment("imbalance", "Imbalance (quick)", "Smoke test of a slow worker process",
                        List.of(Configuration.distributed(primes, 1, 1).withSlowWorkers(1, 3.0)
                                .withPolicy(PolicyName.DYNAMIC).withPartitionsPerSlot(4)
                                .withLabel("1 normal + 1 slow (3x), DYNAMIC"))));
    }
}
