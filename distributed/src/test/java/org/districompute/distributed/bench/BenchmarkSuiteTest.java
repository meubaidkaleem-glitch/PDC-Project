package org.districompute.distributed.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.task.TaskSpec;
import org.junit.jupiter.api.Test;

class BenchmarkSuiteTest {

    @Test
    void everyDistributedWorkloadHasASequentialReference() {
        for (List<Experiment> suite : List.of(BenchmarkSuite.standard(), BenchmarkSuite.quick())) {
            List<Configuration> all = suite.stream().flatMap(e -> e.configurations().stream()).toList();
            for (Configuration c : all) {
                assertTrue(all.stream().anyMatch(s -> s.mode() == ExecutionMode.SEQUENTIAL_CPU
                        && s.workload().name().equals(c.workload().name())), c.label() + " has no reference");
            }
        }
    }

    @Test
    void configurationsCountTheCoresTheyMayUse() {
        Workload primes = Workload.primes(1_000_000L);
        assertEquals(1, Configuration.sequential(primes).cores());
        assertEquals(4, Configuration.multithreaded(primes, 4).cores());
        assertEquals(6, Configuration.distributed(primes, 2, 2).withSlowWorkers(1, 4).cores());
        assertThrows(IllegalArgumentException.class, () -> Configuration.distributed(primes, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Experiment("x", "t", "q",
                List.of(Configuration.sequential(primes), Configuration.sequential(primes))));
    }

    @Test
    void workloadsBuildValidCoreTasks() {
        TaskSpec spec = Workload.matrix(64).spec("t1", ExecutionMode.MULTITHREADED_CPU, 3);
        assertEquals("matrix.multiply", spec.algorithm().id().toString());
        assertEquals(3, spec.constraints().maxThreads());
        assertEquals("primes-1e10", Workload.primes(10_000_000_000L).name());
        assertEquals("primes-25e8", Workload.primes(2_500_000_000L).name());
        assertEquals("primes-12345", Workload.primes(12_345L).name());
        assertFalse(BenchmarkSuite.named("QUICK").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BenchmarkSuite.named("fast"));
    }
}
