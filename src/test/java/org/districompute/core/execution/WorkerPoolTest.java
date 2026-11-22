package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("concurrency")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class WorkerPoolTest {

    private static final int THREADS = 4;

    private WorkerPool pool;

    @BeforeEach
    void startPool() {
        pool = new WorkerPool(THREADS, 4, "test-worker");
    }

    @AfterEach
    void stopPool() {
        pool.close();
    }

    /** Busy work that polls the token, like a real kernel. */
    private static long spin(CancellationToken token, long iterations) {
        long acc = 0;
        for (long i = 0; i < iterations; i++) {
            if ((i & 1023) == 0) {
                token.throwIfCancelled();
            }
            acc += i ^ (acc << 1);
        }
        return acc;
    }

    @Test
    void runsEveryItemExactlyOnceAndReturnsThemInIndexOrder() {
        int items = 2_000;
        AtomicIntegerArray executions = new AtomicIntegerArray(items);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < items; i++) {
            int index = i;
            tasks.add(() -> {
                executions.incrementAndGet(index);
                return index * 2;
            });
        }
        AtomicInteger callbacks = new AtomicInteger();
        List<TaskRun<Integer>> runs = pool.runAll(tasks, THREADS, new CancellationSource(),
                run -> callbacks.incrementAndGet());
        assertEquals(items, runs.size());
        assertEquals(items, callbacks.get());
        for (int i = 0; i < items; i++) {
            assertEquals(1, executions.get(i), "item " + i);
            assertEquals(i, runs.get(i).index());
            assertEquals(i * 2, runs.get(i).value());
            assertTrue(runs.get(i).busyNanos() >= 0 && runs.get(i).queueNanos() >= 0);
        }
    }

    @Test
    void neverExceedsTheParallelismLimitOrCreatesExtraThreads() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            tasks.add(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    return spin(CancellationToken.NONE, 20_000);
                } finally {
                    running.decrementAndGet();
                }
            });
        }
        pool.runAll(tasks, 2, new CancellationSource(), run -> { });
        assertTrue(peak.get() <= 2, "peak in-flight " + peak.get());
        pool.runAll(tasks, 64, new CancellationSource(), run -> { });
        assertTrue(peak.get() <= THREADS, "parallelism is clamped to the pool size; peak " + peak.get());
        assertEquals(THREADS, pool.largestPoolSize(), "the pool never grows");
    }

    @Test
    void firstFailureIsRethrownAndRunningSiblingsStop() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("partition 3 failed");
        CancellationSource scope = new CancellationSource();
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            int index = i;
            tasks.add(() -> {
                started.incrementAndGet();
                running.incrementAndGet();
                try {
                    if (index == 3) {
                        throw boom;
                    }
                    return spin(scope, Long.MAX_VALUE);
                } finally {
                    running.decrementAndGet();
                }
            });
        }
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> pool.runAll(tasks, THREADS, scope, run -> { }));
        assertSame(boom, thrown);
        assertEquals(CancellationReason.FAILURE, scope.reason().orElseThrow());
        assertEquals(0, running.get(), "no item may still be running after runAll returns");
        assertTrue(started.get() < 100, "unsubmitted items must be abandoned, started " + started.get());
    }

    @Test
    void cancellationStopsTheBatchPromptly() throws Exception {
        CancellationSource scope = new CancellationSource();
        AtomicInteger running = new AtomicInteger();
        CountDownLatch firstStarted = new CountDownLatch(1);
        List<Callable<Long>> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> {
                running.incrementAndGet();
                firstStarted.countDown();
                try {
                    return spin(scope, Long.MAX_VALUE);
                } finally {
                    running.decrementAndGet();
                }
            });
        }
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> batch = caller.submit(() -> pool.runAll(tasks, THREADS, scope, run -> { }));
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS));
            scope.cancel(CancellationReason.USER_REQUESTED);
            Exception e = assertThrows(Exception.class, () -> batch.get(10, TimeUnit.SECONDS));
            TaskCancelledException cancelled = (TaskCancelledException) e.getCause();
            assertEquals(CancellationReason.USER_REQUESTED, cancelled.reason());
            assertEquals(0, running.get());
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    void preCancelledScopeRunsNothing() {
        CancellationSource scope = new CancellationSource();
        scope.cancel(CancellationReason.TIMEOUT);
        AtomicInteger started = new AtomicInteger();
        List<Callable<Integer>> tasks = List.of(started::incrementAndGet, started::incrementAndGet);
        TaskCancelledException e = assertThrows(TaskCancelledException.class,
                () -> pool.runAll(tasks, THREADS, scope, run -> { }));
        assertEquals(ErrorCode.TIMEOUT, e.code());
        assertEquals(0, started.get());
    }

    @Test
    void failingCallbackFailsTheBatch() {
        List<Callable<Integer>> tasks = List.of(() -> 1, () -> 2, () -> 3);
        DistriComputeException invalid = new DistriComputeException(ErrorCode.INVALID_PARTIAL_RESULT, "bad");
        assertSame(invalid, assertThrows(DistriComputeException.class,
                () -> pool.runAll(tasks, 1, new CancellationSource(), run -> {
                    throw invalid;
                })));
    }

    @Test
    void checkedExceptionsAreWrappedAsExecutionFailures() {
        List<Callable<Integer>> tasks = List.of(() -> {
            throw new java.io.IOException("disk");
        });
        DistriComputeException e = assertThrows(DistriComputeException.class,
                () -> pool.runAll(tasks, 1, new CancellationSource(), run -> { }));
        assertEquals(ErrorCode.EXECUTION_FAILED, e.code());
    }

    @Test
    void concurrentBatchesShareThePoolWithoutInterference() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<TaskRun<Integer>>>> batches = new ArrayList<>();
            for (int b = 0; b < 4; b++) {
                int offset = b * 1_000;
                batches.add(callers.submit(() -> {
                    List<Callable<Integer>> tasks = new ArrayList<>();
                    for (int i = 0; i < 300; i++) {
                        int value = offset + i;
                        tasks.add(() -> {
                            spin(CancellationToken.NONE, 2_000);
                            return value;
                        });
                    }
                    return pool.runAll(tasks, THREADS, new CancellationSource(), run -> { });
                }));
            }
            for (int b = 0; b < 4; b++) {
                List<TaskRun<Integer>> runs = batches.get(b).get(30, TimeUnit.SECONDS);
                for (int i = 0; i < 300; i++) {
                    assertEquals(b * 1_000 + i, runs.get(i).value());
                }
            }
            assertEquals(THREADS, pool.largestPoolSize());
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void closeTerminatesWorkersAndRejectsNewBatches() {
        pool.runAll(List.<Callable<Integer>>of(() -> 1), 1, new CancellationSource(), run -> { });
        pool.close();
        assertTrue(pool.isTerminated());
        assertThrows(IllegalStateException.class,
                () -> pool.runAll(List.<Callable<Integer>>of(() -> 1), 1, new CancellationSource(), run -> { }));
        long workers = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("test-worker-") && t.isAlive()).count();
        assertEquals(0, workers);
    }
}
