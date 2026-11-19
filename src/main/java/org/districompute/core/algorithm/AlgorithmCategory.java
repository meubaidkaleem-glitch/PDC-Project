package org.districompute.core.algorithm;

/** Workload family of an algorithm, used to group the catalog. */
public enum AlgorithmCategory {
    /** Number-theoretic workloads such as prime counting. */
    NUMBER_THEORY,
    /** Sorting workloads. */
    SORTING,
    /** Dense linear algebra such as matrix addition and multiplication. */
    LINEAR_ALGEBRA,
    /** Numerical methods such as integration, root finding and Monte Carlo estimation. */
    NUMERICAL,
    /** Data processing such as searching, filtering, aggregation and statistics. */
    DATA_PROCESSING
}
