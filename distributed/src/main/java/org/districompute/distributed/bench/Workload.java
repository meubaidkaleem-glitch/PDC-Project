package org.districompute.distributed.bench;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.task.TaskSpec;

/**
 * A Core task without an execution mode: an algorithm and its parameters.
 *
 * @param name short unique name, used to compare results across configurations (same name, same result)
 * @param algorithm Core algorithm id
 * @param parameters Core task parameters
 */
public record Workload(String name, String algorithm, Map<String, Object> parameters) {

    /** Validates and copies the components. */
    public Workload {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(algorithm, "algorithm");
        parameters = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
    }

    /**
     * Prime counting in {@code [0, upperBound]}: compute-bound, tiny work units and results.
     *
     * @param upperBound the upper bound
     * @return the workload
     */
    public static Workload primes(long upperBound) {
        return new Workload("primes-" + compact(upperBound), "primes.count",
                Map.of("lowerBound", 0L, "upperBound", upperBound));
    }

    /**
     * Multiplication of two generated {@code n × n} matrices: work units carry matrix data.
     *
     * @param n the dimension
     * @return the workload
     */
    public static Workload matrix(int n) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("rowsA", n);
        parameters.put("colsA", n);
        parameters.put("colsB", n);
        parameters.put("seed", 7L);
        return new Workload("matmul-" + n, "matrix.multiply", parameters);
    }

    /**
     * Builds the Core task.
     *
     * @param taskId the task id
     * @param mode the execution mode
     * @param threads thread count for {@code MULTITHREADED_CPU} (ignored otherwise)
     * @return the task
     */
    public TaskSpec spec(String taskId, ExecutionMode mode, int threads) {
        TaskSpec.Builder builder = TaskSpec.builder(algorithm).taskId(taskId).experimentId("benchmark")
                .parameters(parameters).mode(mode).createdAt(Instant.now());
        // Core accepts a thread count only for MULTITHREADED_CPU.
        return mode == ExecutionMode.MULTITHREADED_CPU ? builder.maxThreads(threads).build() : builder.build();
    }

    static String compact(long value) {
        int exponent = 0;
        long rest = value;
        while (rest >= 10 && rest % 10 == 0) {
            rest /= 10;
            exponent++;
        }
        return exponent >= 6 ? rest + "e" + exponent : Long.toString(value);
    }
}
