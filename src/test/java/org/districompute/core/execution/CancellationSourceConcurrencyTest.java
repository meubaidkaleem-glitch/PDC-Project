package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("concurrency")
class CancellationSourceConcurrencyTest {

    @Test
    void racingRegistrationAndCancellationRunEachCallbackExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < 1_000; round++) {
                CancellationSource source = new CancellationSource();
                AtomicInteger calls = new AtomicInteger();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        source.onCancel(calls::incrementAndGet);
                        return null;
                    }));
                }
                futures.add(pool.submit(() -> {
                    start.await();
                    source.cancel(CancellationReason.USER_REQUESTED);
                    return null;
                }));
                start.countDown();
                for (Future<?> future : futures) {
                    future.get(10, TimeUnit.SECONDS);
                }
                assertEquals(3, calls.get(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
