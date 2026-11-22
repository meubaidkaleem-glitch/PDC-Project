package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.districompute.core.ErrorCode;
import org.districompute.core.fixtures.SumOfSquaresAlgorithm;
import org.districompute.core.registry.AlgorithmRegistry;
import org.districompute.core.task.TaskSpec;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Tag("concurrency")
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LocalExecutionEngineConcurrencyTest {

    private static TaskSpec longPrimeCount() {
        return TaskSpec.builder("primes.count").parameter("upperBound", 100_000_000_000L).build();
    }

    @Test
    void userCancellationStopsARunningTask() throws Exception {
        try (LocalExecutionEngine engine = LocalExecutionEngine.withDefaults()) {
            CancellationSource cancellation = new CancellationSource();
            CountDownLatch running = new CountDownLatch(1);
            ExecutorService caller = Executors.newSingleThreadExecutor();
            try {
                Future<ExecutionOutcome> outcome = caller.submit(() -> engine.execute(longPrimeCount(), cancellation,
                        event -> {
                            if (event.status() == TaskStatus.RUNNING) {
                                running.countDown();
                            }
                        }));
                assertTrue(running.await(30, TimeUnit.SECONDS));
                long cancelledAt = System.nanoTime();
                cancellation.cancel(CancellationReason.USER_REQUESTED);
                ExecutionOutcome result = outcome.get(30, TimeUnit.SECONDS);
                long latencyMillis = (System.nanoTime() - cancelledAt) / 1_000_000;
                assertEquals(TaskStatus.CANCELLED, result.status());
                assertEquals(ErrorCode.CANCELLED, result.taskResult().error().code());
                assertEquals(TaskStatus.RUNNING, result.taskResult().error().status());
                assertTrue(latencyMillis < 10_000, "cancellation took " + latencyMillis + " ms");
            } finally {
                caller.shutdownNow();
            }
        }
    }

    @Test
    void admissionControlRunsAtMostTheConfiguredNumberOfTasks() throws Exception {
        for (int slots : new int[] {1, 2}) {
            ConcurrencyProbe probe = new ConcurrencyProbe();
            EngineConfig config = EngineConfig.defaults().withMaxConcurrentTasks(slots);
            try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.of(probe), config)) {
                ExecutorService callers = Executors.newFixedThreadPool(6);
                try {
                    List<Future<ExecutionOutcome>> outcomes = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        outcomes.add(callers.submit(() -> engine.execute(
                                TaskSpec.builder(ConcurrencyProbe.ID).parameter("n", 50).build())));
                    }
                    for (Future<ExecutionOutcome> future : outcomes) {
                        assertEquals(TaskStatus.COMPLETED, future.get(60, TimeUnit.SECONDS).status());
                    }
                } finally {
                    callers.shutdownNow();
                }
            }
            assertTrue(probe.peak.get() <= slots, "peak " + probe.peak.get() + " with " + slots + " slot(s)");
            assertTrue(probe.peak.get() >= 1);
        }
    }

    /** Fixture that records how many executions run their kernel at the same time. */
    static final class ConcurrencyProbe extends SumOfSquaresAlgorithm {
        static final String ID = "test.concurrency-probe";
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        ConcurrencyProbe() {
            super(ID, "1.0.0");
        }

        @Override
        public Long computeSequential(long[] input, ExecutionContext context) {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(30);
                return super.computeSequential(input, context);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                running.decrementAndGet();
            }
        }
    }

    @Test
    void progressEventsArriveInOrderWithEveryStatusChange() {
        List<ProgressEvent> events = new CopyOnWriteArrayList<>();
        try (LocalExecutionEngine engine = new LocalExecutionEngine(AlgorithmRegistry.loadDefault(),
                EngineConfig.defaults().withProgressIntervalMillis(0))) {
            ExecutionOutcome outcome = engine.execute(TaskSpec.builder("primes.count")
                    .parameter("upperBound", 20_000_000).build(), new CancellationSource(), events::add);
            assertEquals(TaskStatus.COMPLETED, outcome.status());
        }
        List<TaskStatus> statuses = events.stream().map(ProgressEvent::status).distinct().toList();
        assertEquals(List.of(TaskStatus.CREATED, TaskStatus.VALIDATING, TaskStatus.VALIDATED, TaskStatus.PLANNED,
                TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.AGGREGATING, TaskStatus.VERIFYING,
                TaskStatus.COMPLETED), statuses);
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).sequence() > events.get(i - 1).sequence());
            assertTrue(events.get(i).completedUnits() >= events.get(i - 1).completedUnits());
        }
        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(last.totalUnits(), last.completedUnits());
        assertTrue(events.stream().filter(e -> e.status() == TaskStatus.RUNNING).count() > 2,
                "kernel progress is reported while running");
    }

    @Test
    void closingTheEngineCancelsRunningTasksWithShutdown() throws Exception {
        LocalExecutionEngine engine = LocalExecutionEngine.withDefaults();
        CountDownLatch running = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<ExecutionOutcome> outcome = caller.submit(() -> engine.execute(longPrimeCount(),
                    new CancellationSource(), event -> {
                        if (event.status() == TaskStatus.RUNNING) {
                            running.countDown();
                        }
                    }));
            assertTrue(running.await(30, TimeUnit.SECONDS));
            engine.close();
            ExecutionOutcome result = outcome.get(30, TimeUnit.SECONDS);
            assertEquals(TaskStatus.CANCELLED, result.status());
            assertTrue(engine.workerPool().isTerminated());
        } finally {
            caller.shutdownNow();
            engine.close();
        }
    }
}
