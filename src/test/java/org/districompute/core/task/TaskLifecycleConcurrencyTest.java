package org.districompute.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("concurrency")
class TaskLifecycleConcurrencyTest {

    @Test
    void exactlyOneCompetingTerminalTransitionWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 0; round < 500; round++) {
                TaskLifecycle lifecycle = new TaskLifecycle();
                lifecycle.transitionTo(TaskStatus.VALIDATING);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Boolean>> attempts = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    TaskStatus target = i % 2 == 0 ? TaskStatus.CANCELLED : TaskStatus.FAILED;
                    attempts.add(pool.submit(() -> {
                        start.await();
                        return lifecycle.tryTransitionTo(target);
                    }));
                }
                start.countDown();
                int winners = 0;
                for (Future<Boolean> attempt : attempts) {
                    winners += attempt.get(10, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertEquals(1, winners, "round " + round);
                assertEquals(3, lifecycle.history().size(), "round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
