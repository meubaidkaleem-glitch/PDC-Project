package org.districompute.core.partition;

/**
 * A contiguous interval of integers {@code [start, endExclusive)}.
 *
 * @param start first value, inclusive
 * @param endExclusive end, exclusive
 */
public record RangeBounds(long start, long endExclusive) implements PartitionBounds {

    /** Validates the interval. */
    public RangeBounds {
        if (endExclusive < start) {
            throw new IllegalArgumentException("Invalid range [" + start + ", " + endExclusive + ")");
        }
    }

    @Override
    public long elementCount() {
        return endExclusive - start;
    }
}
