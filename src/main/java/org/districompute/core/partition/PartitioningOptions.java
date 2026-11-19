package org.districompute.core.partition;

/**
 * Optional, user-requested partitioning parameters. Each field is {@code null} when not specified. The algorithm's
 * partition strategy decides which fields apply:
 * <ul>
 *   <li>range and chunk strategies use {@code targetPartitions} or {@code partitionSize} (elements per
 *       partition), and reject a request that sets both;</li>
 *   <li>the 2-D block strategy uses {@code blockRows} and {@code blockCols}, or derives a block shape from
 *       {@code targetPartitions}.</li>
 * </ul>
 *
 * @param targetPartitions requested number of partitions (at least 1)
 * @param partitionSize requested number of elements per partition (at least 1)
 * @param blockRows requested block height for 2-D block partitioning (at least 1)
 * @param blockCols requested block width for 2-D block partitioning (at least 1)
 */
public record PartitioningOptions(Integer targetPartitions, Long partitionSize, Integer blockRows, Integer blockCols) {

    private static final PartitioningOptions DEFAULTS = new PartitioningOptions(null, null, null, null);

    /** Validates that all specified values are positive. */
    public PartitioningOptions {
        requirePositive("targetPartitions", targetPartitions);
        requirePositive("partitionSize", partitionSize);
        requirePositive("blockRows", blockRows);
        requirePositive("blockCols", blockCols);
    }

    /**
     * Options with nothing specified; the algorithm chooses.
     *
     * @return the default options
     */
    public static PartitioningOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Requests a number of partitions.
     *
     * @param partitions number of partitions
     * @return the options
     */
    public static PartitioningOptions ofPartitions(int partitions) {
        return new PartitioningOptions(partitions, null, null, null);
    }

    /**
     * Requests a partition size in elements.
     *
     * @param elementsPerPartition elements per partition
     * @return the options
     */
    public static PartitioningOptions ofPartitionSize(long elementsPerPartition) {
        return new PartitioningOptions(null, elementsPerPartition, null, null);
    }

    /**
     * Requests a 2-D block shape.
     *
     * @param rows block height
     * @param cols block width
     * @return the options
     */
    public static PartitioningOptions ofBlocks(int rows, int cols) {
        return new PartitioningOptions(null, null, rows, cols);
    }

    private static void requirePositive(String name, Number value) {
        if (value != null && value.longValue() < 1) {
            throw new IllegalArgumentException(name + " must be at least 1, got " + value);
        }
    }
}
