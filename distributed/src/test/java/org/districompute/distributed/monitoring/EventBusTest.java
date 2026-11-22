package org.districompute.distributed.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.districompute.distributed.testing.Await;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private final EventBus bus = new EventBus(5, 4, Clock.systemUTC());

    @Test
    void eventsAreNumberedAndTheHistoryIsBounded() {
        for (int i = 1; i <= 7; i++) {
            assertEquals(i, publish("e" + i).sequence());
        }
        assertEquals(7, bus.lastSequence());
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), bus.recent(0, 100).stream().map(RuntimeEvent::sequence).toList());
        assertEquals(List.of(6L, 7L), bus.recent(5, 100).stream().map(RuntimeEvent::sequence).toList());
        assertEquals(List.of(3L, 4L), bus.recent(0, 2).stream().map(RuntimeEvent::sequence).toList());
    }

    @Test
    void subscribersReceiveEventsInOrderAndCanReplayTheHistory() {
        publish("before-1");
        publish("before-2");
        List<Long> live = new CopyOnWriteArrayList<>();
        List<Long> replayed = new CopyOnWriteArrayList<>();
        try (EventBus.Subscription a = bus.subscribe(e -> live.add(e.sequence()));
                EventBus.Subscription b = bus.subscribe(e -> replayed.add(e.sequence()), 0)) {
            publish("after-1");
            Await.until(Duration.ofSeconds(5), () -> live.size() == 1 && replayed.size() == 3, "delivery");
            assertEquals(List.of(3L), live);
            assertEquals(List.of(1L, 2L, 3L), replayed);
            assertEquals(0, a.dropped() + b.dropped());
        }
    }

    @Test
    void aSlowSubscriberLosesEventsButIsToldHowMany() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        List<RuntimeEvent> received = new CopyOnWriteArrayList<>();
        EventBus big = new EventBus(100, 4, Clock.systemUTC());
        try (EventBus.Subscription slow = big.subscribe(e -> {
            awaitQuietly(release);
            received.add(e);
        })) {
            for (int i = 0; i < 20; i++) {
                big.publish(EventType.TASK_PROGRESS, EventIds.NONE, "p" + i, Map.of());
            }
            assertTrue(slow.dropped() > 0, "the publisher never waits for a slow subscriber");
            release.countDown();
            // No further publishing: the subscriber must still learn about the gap.
            Await.until(Duration.ofSeconds(5), () -> received.stream()
                    .anyMatch(e -> e.type() == EventType.EVENTS_DROPPED), "the drop notification");
            RuntimeEvent notice = received.stream().filter(e -> e.type() == EventType.EVENTS_DROPPED).findFirst()
                    .orElseThrow();
            assertEquals(slow.dropped(), ((Number) notice.details().get("count")).longValue());
            List<Long> order = received.stream().map(RuntimeEvent::sequence).toList();
            assertEquals(order.stream().sorted().toList(), order, "the notice keeps the sequence order");
        }
    }

    @Test
    void aFailingListenerDoesNotStopDelivery() {
        List<Long> seen = new CopyOnWriteArrayList<>();
        try (EventBus.Subscription s = bus.subscribe(e -> {
            seen.add(e.sequence());
            throw new IllegalStateException("listener bug");
        })) {
            publish("a");
            publish("b");
            Await.until(Duration.ofSeconds(5), () -> seen.size() == 2, "both events despite the exceptions");
            assertEquals(0, s.dropped());
        }
    }

    @Test
    void closedSubscriptionsReceiveNothing() throws InterruptedException {
        List<Long> seen = new CopyOnWriteArrayList<>();
        EventBus.Subscription s = bus.subscribe(e -> seen.add(e.sequence()));
        s.close();
        publish("ignored");
        Thread.sleep(50);
        assertTrue(seen.isEmpty());
    }

    private RuntimeEvent publish(String message) {
        return bus.publish(EventType.TASK_PROGRESS, new EventIds("w", "x", "t", null, null), message, Map.of("k", 1));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
