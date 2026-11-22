package org.districompute.core.algorithm.sort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.partition.ChunkBounds;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.serialization.BinaryPayloadReader;
import org.districompute.core.serialization.BinaryPayloadWriter;
import org.districompute.core.serialization.PayloadCodec;

/**
 * Partial result of a sort partition: the chunk that started at {@code offset}, now sorted.
 *
 * @param offset position of the chunk in the whole input
 * @param values the chunk's elements in ascending order
 */
public record SortedRun(long offset, long[] values) {

    /** Binary codec, format {@code sort-run/1}: offset (int64), length (int32), elements (int64). */
    public static final PayloadCodec<SortedRun> CODEC = new PayloadCodec<>() {
        @Override
        public String format() {
            return "sort-run/1";
        }

        @Override
        public byte[] encode(SortedRun run) {
            return new BinaryPayloadWriter(format(), 12L + 8L * run.values().length).putLong(run.offset())
                    .putInt(run.values().length).putLongs(run.values(), 0, run.values().length).finish();
        }

        @Override
        public SortedRun decode(byte[] bytes) {
            BinaryPayloadReader reader = new BinaryPayloadReader(bytes, format());
            long offset = reader.getLong();
            int length = reader.getBoundedInt("length", Integer.MAX_VALUE);
            long[] values = reader.getLongs(length);
            reader.requireFullyConsumed();
            return new SortedRun(offset, values);
        }
    };

    /** Validates the components. */
    public SortedRun {
        Objects.requireNonNull(values, "values");
    }

    /**
     * Checks a run against the chunk it claims to answer: same offset, same length, ascending order.
     *
     * @param bounds partition bounds
     * @param run received run
     * @return the problem, or empty if valid
     */
    static Optional<String> problem(PartitionBounds bounds, SortedRun run) {
        if (!(bounds instanceof ChunkBounds chunk)) {
            return Optional.of("sort partitions need CHUNK bounds");
        }
        if (run.offset() != chunk.offset() || run.values().length != chunk.length()) {
            return Optional.of("run [offset " + run.offset() + ", length " + run.values().length
                    + "] does not match chunk [offset " + chunk.offset() + ", length " + chunk.length() + "]");
        }
        long[] values = run.values();
        for (int i = 1; i < values.length; i++) {
            if (values[i - 1] > values[i]) {
                return Optional.of("run is not sorted at index " + i);
            }
        }
        return Optional.empty();
    }

    /**
     * Merges sorted runs with a binary heap of run heads: O(n log k) for n elements in k runs. Equal elements are
     * taken from the lower-indexed run first, so the merge is deterministic.
     *
     * @param runs sorted runs
     * @param cancellation polled every 2<sup>16</sup> output elements
     * @return all elements in ascending order
     */
    static long[] merge(List<SortedRun> runs, CancellationToken cancellation) {
        long total = 0;
        for (SortedRun run : runs) {
            total += run.values().length;
        }
        long[] out = new long[Math.toIntExact(total)];
        int k = runs.size();
        long[][] sources = new long[k][];
        int[] positions = new int[k];
        int[] heap = new int[k];
        int size = 0;
        for (int r = 0; r < k; r++) {
            sources[r] = runs.get(r).values();
            if (sources[r].length > 0) {
                heap[size++] = r;
            }
        }
        for (int i = size / 2 - 1; i >= 0; i--) {
            siftDown(heap, size, i, sources, positions);
        }
        int written = 0;
        while (size > 0) {
            if ((written & 0xFFFF) == 0) {
                cancellation.throwIfCancelled();
            }
            int r = heap[0];
            out[written++] = sources[r][positions[r]++];
            if (positions[r] == sources[r].length) {
                heap[0] = heap[--size];
            }
            siftDown(heap, size, 0, sources, positions);
        }
        return out;
    }

    private static void siftDown(int[] heap, int size, int start, long[][] sources, int[] positions) {
        int i = start;
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            int smallest = left;
            int right = left + 1;
            if (right < size && less(heap[right], heap[left], sources, positions)) {
                smallest = right;
            }
            if (!less(heap[smallest], heap[i], sources, positions)) {
                return;
            }
            int tmp = heap[i];
            heap[i] = heap[smallest];
            heap[smallest] = tmp;
            i = smallest;
        }
    }

    private static boolean less(int a, int b, long[][] sources, int[] positions) {
        long va = sources[a][positions[a]];
        long vb = sources[b][positions[b]];
        return va < vb || (va == vb && a < b);
    }
}
