package org.districompute.core.result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStatus;
import org.districompute.core.partition.Partitioners;
import org.districompute.core.result.PartialResultCollector.Classification;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PartialResultCollectorTest {

    private static final List<PartitionBounds> PLAN = List.copyOf(Partitioners.equalRanges(0, 100, 4));

    /** Partials are the range length; valid iff they equal the partition's element count. */
    private static PartialResultCollector<Long> collector() {
        return new PartialResultCollector<>("task-1", PLAN,
                (bounds, partial) -> partial == bounds.elementCount() ? Optional.empty()
                        : Optional.of("expected " + bounds.elementCount()),
                partial -> Long.toString(partial));
    }

    @Test
    void classifiesEveryKindOfOffer() {
        PartialResultCollector<Long> collector = collector();
        assertEquals(Classification.ACCEPTED, collector.offer("task-1", 0, 25L).classification());
        assertEquals(Classification.DUPLICATE, collector.offer("task-1", 0, 25L).classification());
        assertEquals(Classification.INVALID, collector.offer("task-1", 1, 24L).classification());
        assertEquals(Classification.INVALID, collector.offer("other-task", 1, 25L).classification());
        assertEquals(Classification.UNKNOWN_PARTITION, collector.offer("task-1", 4, 25L).classification());
        assertEquals(Classification.UNKNOWN_PARTITION, collector.offer("task-1", -1, 25L).classification());
        assertEquals(Classification.INVALID, collector.offer("task-1", 2, null).classification());
        assertArrayEquals(new int[] {1, 1, 0, 5}, collector.counters());
        assertEquals(4, collector.attempts(0) + collector.attempts(1), "every offer counts, valid or not");
    }

    @Test
    void detectsConflictingDuplicates() {
        PartialResultCollector<Long> collector = new PartialResultCollector<>("task-1", PLAN,
                (bounds, partial) -> Optional.empty(), partial -> Long.toString(partial));
        collector.offer("task-1", 3, 25L);
        PartialResultCollector.Offer conflict = collector.offer("task-1", 3, 26L);
        assertEquals(Classification.CONFLICTING_DUPLICATE, conflict.classification());
        assertTrue(conflict.detail().contains("differs"));
    }

    @Test
    void refusesToAggregateWhilePartitionsAreMissingOrFailed() {
        PartialResultCollector<Long> collector = collector();
        collector.offer("task-1", 0, 25L);
        collector.offer("task-1", 2, 25L);
        collector.markFailed(3, ErrorInfo.of(ErrorCode.PARTITION_FAILED, "worker lost"));
        assertEquals(List.of(1, 3), collector.missing());
        assertEquals(List.of(3), collector.failed());
        assertEquals(PartitionStatus.FAILED, collector.status(3));
        DistriComputeException e = assertThrows(DistriComputeException.class, collector::orderedResults);
        assertEquals(ErrorCode.MISSING_PARTIAL_RESULT, e.code());
        assertTrue(e.getMessage().contains("[1, 3]") && e.getMessage().contains("failed [3]"), e.getMessage());

        collector.offer("task-1", 1, 25L);
        collector.offer("task-1", 3, 25L);
        assertTrue(collector.isComplete());
        assertTrue(collector.failure(3).isEmpty(), "a later success clears the failure");
        assertEquals(List.of(25L, 25L, 25L, 25L), collector.orderedResults());
        collector.markFailed(0, ErrorInfo.of(ErrorCode.PARTITION_FAILED, "late failure report"));
        assertEquals(PartitionStatus.COMPLETED, collector.status(0), "failures never override accepted results");
    }

    @Test
    void closedCollectorReportsLateOffers() {
        PartialResultCollector<Long> collector = collector();
        collector.close();
        assertEquals(Classification.LATE, collector.offer("task-1", 0, 25L).classification());
        assertFalse(collector.isComplete());
    }

    @Test
    @Tag("concurrency")
    void racingOffersAcceptExactlyOneResultPerPartition() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 200; round++) {
                PartialResultCollector<Long> collector = collector();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Classification>> offers = new ArrayList<>();
                for (int t = 0; t < 16; t++) {
                    int index = t % 4;
                    offers.add(pool.submit(() -> {
                        start.await();
                        return collector.offer("task-1", index, 25L).classification();
                    }));
                }
                start.countDown();
                int accepted = 0;
                for (Future<Classification> offer : offers) {
                    accepted += offer.get(10, TimeUnit.SECONDS) == Classification.ACCEPTED ? 1 : 0;
                }
                assertEquals(4, accepted, "round " + round);
                assertTrue(collector.isComplete());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
