package org.districompute.core.partition;

import java.util.ArrayList;
import java.util.List;
import org.districompute.core.validation.Violation;

/**
 * Deterministic, remainder-aware partition planners plus exact-cover checks.
 *
 * <p>Equal-size planners spread the remainder over the first parts, so part sizes differ by at most one element
 * (row) and no part is empty unless the whole input is empty. Fixed-size planners make every part
 * {@code size} long except possibly the last. Every planner's output covers the input exactly once. The
 * {@code requireExactCover} checks enforce this and are run by tests and by the coordinator.
 */
public final class Partitioners {

    private Partitioners() {
    }

    /**
     * Splits {@code [start, endExclusive)} into {@code min(parts, length)} contiguous ranges of near-equal size.
     *
     * @param start first value, inclusive
     * @param endExclusive end, exclusive
     * @param parts requested number of parts, at least 1
     * @return the ranges in ascending order; a single empty range if the interval is empty
     */
    public static List<RangeBounds> equalRanges(long start, long endExclusive, int parts) {
        long length = endExclusive - start;
        int count = effectiveParts(length, parts);
        List<RangeBounds> ranges = new ArrayList<>(count);
        long base = length / count;
        long remainder = length % count;
        long cursor = start;
        for (int i = 0; i < count; i++) {
            long size = base + (i < remainder ? 1 : 0);
            ranges.add(new RangeBounds(cursor, cursor + size));
            cursor += size;
        }
        return ranges;
    }

    /**
     * Splits {@code [start, endExclusive)} into ranges of {@code size} values (the last may be shorter).
     *
     * @param start first value, inclusive
     * @param endExclusive end, exclusive
     * @param size values per range, at least 1
     * @return the ranges in ascending order; a single empty range if the interval is empty
     */
    public static List<RangeBounds> fixedSizeRanges(long start, long endExclusive, long size) {
        requirePositive(size);
        long length = endExclusive - start;
        if (length == 0) {
            return List.of(new RangeBounds(start, start));
        }
        List<RangeBounds> ranges = new ArrayList<>();
        for (long cursor = start; cursor < endExclusive; cursor += Math.min(size, endExclusive - cursor)) {
            ranges.add(new RangeBounds(cursor, Math.min(endExclusive, cursor + size)));
        }
        return ranges;
    }

    /**
     * Splits an array of {@code length} elements into near-equal chunks.
     *
     * @param length array length
     * @param parts requested number of chunks, at least 1
     * @return the chunks in ascending order; a single empty chunk for an empty array
     */
    public static List<ChunkBounds> equalChunks(long length, int parts) {
        return equalRanges(0, length, parts).stream()
                .map(range -> new ChunkBounds(range.start(), range.elementCount())).toList();
    }

    /**
     * Splits an array of {@code length} elements into chunks of {@code size} elements.
     *
     * @param length array length
     * @param size elements per chunk, at least 1
     * @return the chunks in ascending order; a single empty chunk for an empty array
     */
    public static List<ChunkBounds> fixedSizeChunks(long length, long size) {
        return fixedSizeRanges(0, length, size).stream()
                .map(range -> new ChunkBounds(range.start(), range.elementCount())).toList();
    }

    /**
     * Tiles a {@code rows × cols} matrix with blocks of at most {@code blockRows × blockCols}, in row-major block
     * order. Edge blocks hold the remainder.
     *
     * @param rows matrix rows, at least 1
     * @param cols matrix columns, at least 1
     * @param blockRows block height, at least 1 (clamped to {@code rows})
     * @param blockCols block width, at least 1 (clamped to {@code cols})
     * @return the blocks
     */
    public static List<BlockBounds> blocks(int rows, int cols, int blockRows, int blockCols) {
        requirePositive(blockRows);
        requirePositive(blockCols);
        int height = Math.min(blockRows, rows);
        int width = Math.min(blockCols, cols);
        List<BlockBounds> blocks = new ArrayList<>();
        for (int r = 0; r < rows; r += height) {
            for (int c = 0; c < cols; c += width) {
                blocks.add(new BlockBounds(r, Math.min(rows, r + height), c, Math.min(cols, c + width)));
            }
        }
        return blocks;
    }

    /**
     * Splits a matrix into {@code min(parts, rows)} full-width row bands of near-equal height.
     *
     * @param rows matrix rows
     * @param cols matrix columns
     * @param parts requested number of bands
     * @return the bands
     */
    public static List<BlockBounds> rowBands(int rows, int cols, int parts) {
        return equalRanges(0, rows, parts).stream()
                .map(range -> new BlockBounds((int) range.start(), (int) range.endExclusive(), 0, cols)).toList();
    }

    /**
     * Checks that ranges cover {@code [start, endExclusive)} exactly once, in ascending order.
     *
     * @param ranges ranges to check
     * @param start expected first value
     * @param endExclusive expected end
     * @throws IllegalStateException on a gap, an overlap or a wrong extent
     */
    public static void requireExactCover(List<RangeBounds> ranges, long start, long endExclusive) {
        long cursor = start;
        for (RangeBounds range : ranges) {
            if (range.start() != cursor) {
                throw new IllegalStateException((range.start() < cursor ? "Overlap" : "Gap") + " at " + cursor);
            }
            cursor = range.endExclusive();
        }
        if (cursor != endExclusive) {
            throw new IllegalStateException("Ranges end at " + cursor + ", expected " + endExclusive);
        }
    }

    /**
     * Checks that blocks tile a {@code rows × cols} matrix exactly once.
     *
     * @param blocks blocks to check
     * @param rows matrix rows
     * @param cols matrix columns
     * @throws IllegalStateException if any element is uncovered, covered twice, or out of bounds
     */
    public static void requireExactCover(List<BlockBounds> blocks, int rows, int cols) {
        long covered = 0;
        for (BlockBounds block : blocks) {
            if (block.rowEnd() > rows || block.colEnd() > cols) {
                throw new IllegalStateException("Block " + block + " exceeds " + rows + "x" + cols);
            }
            covered += block.elementCount();
        }
        if (covered != (long) rows * cols) {
            throw new IllegalStateException("Blocks cover " + covered + " elements, expected " + (long) rows * cols);
        }
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                if (overlaps(blocks.get(i), blocks.get(j))) {
                    throw new IllegalStateException("Blocks overlap: " + blocks.get(i) + " and " + blocks.get(j));
                }
            }
        }
    }

    /**
     * Validates partitioning options for a range or chunk strategy.
     *
     * @param options requested options
     * @return violations; empty if valid
     */
    public static List<Violation> validateLinear(PartitioningOptions options) {
        List<Violation> violations = new ArrayList<>();
        if (options.targetPartitions() != null && options.partitionSize() != null) {
            violations.add(new Violation("constraints.partitioning", Violation.Codes.INCONSISTENT,
                    "set either targetPartitions or partitionSize, not both"));
        }
        if (options.blockRows() != null || options.blockCols() != null) {
            violations.add(new Violation("constraints.partitioning", Violation.Codes.INCONSISTENT,
                    "blockRows and blockCols apply only to 2-D block partitioning"));
        }
        return violations;
    }

    /**
     * Validates partitioning options for the 2-D block strategy.
     *
     * @param options requested options
     * @return violations; empty if valid
     */
    public static List<Violation> validateBlocks(PartitioningOptions options) {
        List<Violation> violations = new ArrayList<>();
        if (options.partitionSize() != null) {
            violations.add(new Violation("constraints.partitioning.partitionSize", Violation.Codes.INCONSISTENT,
                    "use blockRows and blockCols (or targetPartitions) for matrix partitioning"));
        }
        boolean explicitBlocks = options.blockRows() != null || options.blockCols() != null;
        if (explicitBlocks && (options.blockRows() == null || options.blockCols() == null)) {
            violations.add(new Violation("constraints.partitioning", Violation.Codes.INCONSISTENT,
                    "blockRows and blockCols must be set together"));
        }
        if (explicitBlocks && options.targetPartitions() != null) {
            violations.add(new Violation("constraints.partitioning", Violation.Codes.INCONSISTENT,
                    "set either targetPartitions or blockRows/blockCols, not both"));
        }
        return violations;
    }

    /**
     * Plans linear partitions from options: {@code partitionSize} gives fixed-size ranges, {@code targetPartitions}
     * gives equal ranges, otherwise {@code defaultParts} equal ranges, never with fewer than {@code minPartSize}
     * values per part (unless the interval itself is shorter).
     *
     * @param start first value
     * @param endExclusive end
     * @param options validated options
     * @param defaultParts parts to use when nothing is requested
     * @param minPartSize smallest worthwhile part when choosing the default
     * @return the ranges
     */
    public static List<RangeBounds> planLinear(long start, long endExclusive, PartitioningOptions options,
            int defaultParts, long minPartSize) {
        if (options.partitionSize() != null) {
            return fixedSizeRanges(start, endExclusive, options.partitionSize());
        }
        if (options.targetPartitions() != null) {
            return equalRanges(start, endExclusive, options.targetPartitions());
        }
        long length = endExclusive - start;
        long byGrain = Math.max(1, length / Math.max(1, minPartSize));
        return equalRanges(start, endExclusive, (int) Math.max(1, Math.min(defaultParts, byGrain)));
    }

    /**
     * Plans 2-D blocks from options: explicit block shape, otherwise row bands ({@code targetPartitions} or
     * {@code defaultParts} of them).
     *
     * @param rows matrix rows
     * @param cols matrix columns
     * @param options validated options
     * @param defaultParts bands to use when nothing is requested
     * @return the blocks
     */
    public static List<BlockBounds> planBlocks(int rows, int cols, PartitioningOptions options, int defaultParts) {
        if (options.blockRows() != null && options.blockCols() != null) {
            return blocks(rows, cols, options.blockRows(), options.blockCols());
        }
        int parts = options.targetPartitions() != null ? options.targetPartitions() : defaultParts;
        return rowBands(rows, cols, parts);
    }

    private static boolean overlaps(BlockBounds a, BlockBounds b) {
        return a.rowStart() < b.rowEnd() && b.rowStart() < a.rowEnd() && a.colStart() < b.colEnd()
                && b.colStart() < a.colEnd();
    }

    private static int effectiveParts(long length, int parts) {
        requirePositive(parts);
        if (length < 0) {
            throw new IllegalArgumentException("Negative length " + length);
        }
        return (int) Math.max(1, Math.min(parts, length));
    }

    private static void requirePositive(long value) {
        if (value < 1) {
            throw new IllegalArgumentException("Partition parameters must be at least 1, got " + value);
        }
    }
}
