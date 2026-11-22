package org.districompute.core.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class PartitionersTest {

    @Test
    void equalRangesCoverExactlyWithSizesDifferingByAtMostOne() {
        for (long length = 0; length <= 300; length++) {
            for (int parts = 1; parts <= 40; parts++) {
                List<RangeBounds> ranges = Partitioners.equalRanges(1_000, 1_000 + length, parts);
                Partitioners.requireExactCover(ranges, 1_000, 1_000 + length);
                assertEquals(Math.max(1, Math.min(parts, length)), ranges.size(), length + "/" + parts);
                long min = ranges.stream().mapToLong(RangeBounds::elementCount).min().orElseThrow();
                long max = ranges.stream().mapToLong(RangeBounds::elementCount).max().orElseThrow();
                assertTrue(max - min <= 1, "remainder must be spread: " + ranges);
                assertTrue(length == 0 || min >= 1, "no empty parts unless the input is empty");
            }
        }
    }

    @Test
    void fixedSizeRangesKeepTheRemainderInTheLastPart() {
        List<RangeBounds> ranges = Partitioners.fixedSizeRanges(0, 10, 4);
        assertEquals(List.of(new RangeBounds(0, 4), new RangeBounds(4, 8), new RangeBounds(8, 10)), ranges);
        Partitioners.requireExactCover(Partitioners.fixedSizeRanges(5, 1_005, 7), 5, 1_005);
        assertEquals(List.of(new RangeBounds(3, 3)), Partitioners.fixedSizeRanges(3, 3, 5));
    }

    @Test
    void chunksMirrorRanges() {
        assertEquals(List.of(new ChunkBounds(0, 4), new ChunkBounds(4, 3), new ChunkBounds(7, 3)),
                Partitioners.equalChunks(10, 3));
        assertEquals(List.of(new ChunkBounds(0, 0)), Partitioners.equalChunks(0, 8));
        assertEquals(3, Partitioners.fixedSizeChunks(9, 3).size());
    }

    @Test
    void blocksTileEveryMatrixExactlyIncludingRemainders() {
        for (int rows = 1; rows <= 23; rows += 2) {
            for (int cols = 1; cols <= 19; cols += 3) {
                for (int br = 1; br <= 9; br += 4) {
                    for (int bc = 1; bc <= 9; bc += 4) {
                        List<BlockBounds> blocks = Partitioners.blocks(rows, cols, br, bc);
                        Partitioners.requireExactCover(blocks, rows, cols);
                        int expected = (int) (Math.ceil((double) rows / Math.min(br, rows))
                                * Math.ceil((double) cols / Math.min(bc, cols)));
                        assertEquals(expected, blocks.size());
                    }
                }
            }
        }
        Partitioners.requireExactCover(Partitioners.rowBands(10, 4, 3), 10, 4);
        assertEquals(10, Partitioners.rowBands(10, 4, 50).size(), "at most one band per row");
    }

    @Test
    void coverChecksDetectGapsOverlapsAndWrongExtents() {
        assertThrows(IllegalStateException.class, () -> Partitioners.requireExactCover(
                List.of(new RangeBounds(0, 4), new RangeBounds(5, 10)), 0, 10));
        assertThrows(IllegalStateException.class, () -> Partitioners.requireExactCover(
                List.of(new RangeBounds(0, 6), new RangeBounds(5, 10)), 0, 10));
        assertThrows(IllegalStateException.class, () -> Partitioners.requireExactCover(
                List.of(new RangeBounds(0, 9)), 0, 10));
        assertThrows(IllegalStateException.class, () -> Partitioners.requireExactCover(
                List.of(new BlockBounds(0, 2, 0, 2), new BlockBounds(1, 3, 0, 2)), 3, 2));
        assertThrows(IllegalStateException.class, () -> Partitioners.requireExactCover(
                List.of(new BlockBounds(0, 2, 0, 2)), 3, 2));
    }

    @Test
    void plansAreDeterministicAndRespectTheMinimumPartSize() {
        PartitioningOptions none = PartitioningOptions.defaults();
        assertEquals(Partitioners.planLinear(0, 1_000_000, none, 16, 100_000),
                Partitioners.planLinear(0, 1_000_000, none, 16, 100_000));
        assertEquals(10, Partitioners.planLinear(0, 1_000_000, none, 16, 100_000).size());
        assertEquals(1, Partitioners.planLinear(0, 50, none, 16, 100_000).size(), "small inputs stay whole");
        assertEquals(16, Partitioners.planLinear(0, 50, PartitioningOptions.ofPartitions(16), 4, 100_000).size(),
                "an explicit request wins over the default grain");
        assertEquals(5, Partitioners.planLinear(0, 50, PartitioningOptions.ofPartitionSize(10), 4, 1).size());
        assertEquals(6, Partitioners.planBlocks(30, 20, PartitioningOptions.ofBlocks(10, 10), 4).size());
        assertEquals(4, Partitioners.planBlocks(30, 20, PartitioningOptions.defaults(), 4).size());
    }

    @Test
    void validatesOptionsPerStrategy() {
        assertEquals(Violation.Codes.INCONSISTENT, Partitioners.validateLinear(
                new PartitioningOptions(4, 10L, null, null)).get(0).code());
        assertEquals(1, Partitioners.validateLinear(PartitioningOptions.ofBlocks(2, 2)).size());
        assertTrue(Partitioners.validateLinear(PartitioningOptions.ofPartitions(3)).isEmpty());
        assertEquals(1, Partitioners.validateBlocks(PartitioningOptions.ofPartitionSize(5)).size());
        assertEquals(1, Partitioners.validateBlocks(new PartitioningOptions(null, null, 4, null)).size());
        assertEquals(1, Partitioners.validateBlocks(new PartitioningOptions(2, null, 4, 4)).size());
        assertTrue(Partitioners.validateBlocks(PartitioningOptions.ofBlocks(4, 4)).isEmpty());
    }
}
