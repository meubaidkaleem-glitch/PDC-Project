package org.districompute.core.partition;

/** How an algorithm divides its input into partitions. */
public enum PartitionStrategyType {
    /** Contiguous sub-ranges of a numeric interval (for example prime counting over [a, b]). */
    RANGE,
    /** Contiguous chunks of an array of elements (for example sorting or data processing). */
    CHUNK,
    /** Two-dimensional row and column blocks of a matrix (for example matrix multiplication). */
    BLOCK_2D
}
