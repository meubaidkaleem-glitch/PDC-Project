package org.districompute.core.partition;

/**
 * A contiguous chunk {@code [offset, offset + length)} of an array.
 *
 * @param offset index of the first element
 * @param length number of elements
 */
public record ChunkBounds(long offset, long length) implements PartitionBounds {

    /** Validates the chunk. */
    public ChunkBounds {
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException("Invalid chunk offset=" + offset + " length=" + length);
        }
    }

    @Override
    public long elementCount() {
        return length;
    }

    /**
     * Index after the last element.
     *
     * @return {@code offset + length}
     */
    public long end() {
        return offset + length;
    }
}
