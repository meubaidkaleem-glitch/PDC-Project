package org.districompute.core.resource;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmRef;

/**
 * Resource requirements and characteristics of a workload, computed from validated parameters without allocating
 * the input (integration contract, section 5). Estimates are labelled as such. They come from formulas, never from
 * measurements.
 *
 * @param algorithm the algorithm and resolved version
 * @param dimensions named problem dimensions, for example {@code rowsA}
 * @param estimatedOperations estimated dominant operation count
 * @param operationUnit what one operation is, for example {@code "floating-point operations"}
 * @param timeComplexity asymptotic time complexity, for example {@code "O(m·k·n)"}
 * @param inputBytes size of the materialized input in bytes
 * @param outputBytes size of the result in bytes
 * @param estimatedPeakMemoryBytes estimated peak heap needed for sequential execution in bytes
 * @param parallelism parallelizability characteristics
 */
public record WorkloadProfile(
        AlgorithmRef algorithm,
        Map<String, Long> dimensions,
        double estimatedOperations,
        String operationUnit,
        String timeComplexity,
        long inputBytes,
        long outputBytes,
        long estimatedPeakMemoryBytes,
        ParallelismProfile parallelism) {

    /** Validates the components. */
    public WorkloadProfile {
        Objects.requireNonNull(algorithm, "algorithm");
        dimensions = Collections.unmodifiableMap(new LinkedHashMap<>(dimensions));
        if (!(estimatedOperations >= 0)) {
            throw new IllegalArgumentException("estimatedOperations must be a non-negative number");
        }
        Objects.requireNonNull(operationUnit, "operationUnit");
        Objects.requireNonNull(timeComplexity, "timeComplexity");
        if (inputBytes < 0 || outputBytes < 0 || estimatedPeakMemoryBytes < 0) {
            throw new IllegalArgumentException("Byte counts must be non-negative");
        }
        Objects.requireNonNull(parallelism, "parallelism");
    }
}
