package org.districompute.core.resource;

/** How partial results depend on each other, which determines how freely work can be distributed. */
public enum DataDependency {
    /** Partitions are independent and results are placed side by side (for example matrix blocks). */
    NONE,
    /** Partitions are independent and results are combined with an associative operation (sum, min, max). */
    REDUCTION,
    /** Partitions are independent but results must be merged in order (for example a k-way merge of sorted runs). */
    ORDERED_MERGE,
    /** Each step depends on the previous one; the computation cannot be freely partitioned. */
    ITERATIVE
}
