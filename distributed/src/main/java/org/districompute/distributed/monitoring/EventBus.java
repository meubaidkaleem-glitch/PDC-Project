package org.districompute.distributed.monitoring;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes runtime events to subscribers without ever blocking the runtime.
 *
 * <ul>
 *   <li>Every event gets a global sequence number and is kept in a bounded history, so late subscribers and
 *       polling clients can catch up ({@link #recent}, {@link #subscribe(EventListener, long)}).</li>
 *   <li>Each subscriber has a bounded queue drained by its own thread. A subscriber that cannot keep up loses
 *       events instead of slowing the coordinator down; it then receives an {@link EventType#EVENTS_DROPPED} event
 *       naming how many it missed, so it can re-read the current state.</li>
 * </ul>
 */
public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final int historySize;
    private final int queueCapacity;
    private final Clock clock;
    private final Deque<RuntimeEvent> history = new ArrayDeque<>();
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private long sequence;

    /**
     * Creates a bus.
     *
     * @param historySize events kept for catching up
     * @param queueCapacity events buffered per subscriber
     * @param clock clock for timestamps
     */
    public EventBus(int historySize, int queueCapacity, Clock clock) {
        if (historySize < 1 || queueCapacity < 2) {
            throw new IllegalArgumentException("historySize must be >= 1 and queueCapacity >= 2");
        }
        this.historySize = historySize;
        this.queueCapacity = queueCapacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Publishes an event.
     *
     * @param type event type
     * @param ids worker, experiment, task, partition and attempt ids (any may be {@code null})
     * @param message summary
     * @param details type-specific values
     * @return the published event
     */
    public RuntimeEvent publish(EventType type, EventIds ids, String message, Map<String, Object> details) {
        RuntimeEvent event;
        synchronized (this) {
            event = new RuntimeEvent(++sequence, clock.instant(), type, ids.workerId(), ids.experimentId(),
                    ids.taskId(), ids.partitionId(), ids.attemptId(), message, details);
            history.addLast(event);
            if (history.size() > historySize) {
                history.removeFirst();
            }
            for (Subscription subscription : subscriptions) {
                subscription.offer(event);
            }
        }
        return event;
    }

    /**
     * Events after a sequence number, from the history.
     *
     * @param afterSequence return events with a larger sequence
     * @param limit maximum number of events
     * @return the events in order (older ones may have left the history)
     */
    public synchronized List<RuntimeEvent> recent(long afterSequence, int limit) {
        List<RuntimeEvent> result = new ArrayList<>();
        for (RuntimeEvent event : history) {
            if (event.sequence() > afterSequence) {
                result.add(event);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return result;
    }

    /**
     * The sequence number of the latest event.
     *
     * @return the sequence, 0 before the first event
     */
    public synchronized long lastSequence() {
        return sequence;
    }

    /**
     * Subscribes to events published from now on.
     *
     * @param listener receives the events
     * @return the subscription
     */
    public Subscription subscribe(EventListener listener) {
        return subscribe(listener, Long.MAX_VALUE);
    }

    /**
     * Subscribes, first replaying the events after a sequence number that are still in the history.
     *
     * @param listener receives the events
     * @param afterSequence replay events with a larger sequence ({@code Long.MAX_VALUE} for none)
     * @return the subscription
     */
    public Subscription subscribe(EventListener listener, long afterSequence) {
        Subscription subscription = new Subscription(Objects.requireNonNull(listener, "listener"), queueCapacity);
        synchronized (this) {
            for (RuntimeEvent event : history) {
                if (event.sequence() > afterSequence) {
                    subscription.offer(event);
                }
            }
            subscriptions.add(subscription);
        }
        subscription.start();
        return subscription;
    }

    /** Closes every subscription. */
    @Override
    public void close() {
        subscriptions.forEach(Subscription::close);
    }

    /** A subscriber's bounded queue and delivery thread. */
    public final class Subscription implements AutoCloseable {
        private final EventListener listener;
        private final BlockingQueue<RuntimeEvent> queue;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicLong dropped = new AtomicLong();
        private final Object dropLock = new Object();
        private long droppedFrom = -1;
        private long droppedTo = -1;
        private volatile Thread thread;

        private Subscription(EventListener listener, int capacity) {
            this.listener = listener;
            this.queue = new ArrayBlockingQueue<>(capacity);
        }

        private void start() {
            thread = Thread.ofVirtual().name("event-subscriber").start(this::deliver);
        }

        /**
         * Queues an event; called with the bus lock held, so events are offered in sequence order. While events are
         * being dropped, the drop notice is queued before any later event, so the subscriber sees the gap in order.
         */
        private void offer(RuntimeEvent event) {
            if (closed.get()) {
                return;
            }
            synchronized (dropLock) {
                if (droppedFrom >= 0) {
                    if (queue.remainingCapacity() < 2) {
                        recordDrop(event);
                        return;
                    }
                    queue.add(dropNotice(event));
                }
                if (!queue.offer(event)) {
                    recordDrop(event);
                }
            }
        }

        private void recordDrop(RuntimeEvent event) {
            dropped.incrementAndGet();
            if (droppedFrom < 0) {
                droppedFrom = event.sequence();
            }
            droppedTo = event.sequence();
        }

        /** Builds the notice for the pending gap and clears it. Called with {@code dropLock} held. */
        private RuntimeEvent dropNotice(RuntimeEvent context) {
            long missed = droppedTo - droppedFrom + 1;
            RuntimeEvent notice = new RuntimeEvent(droppedTo, context.timestamp(), EventType.EVENTS_DROPPED, null,
                    null, null, null, null, missed + " events were dropped because this subscriber was too slow; "
                            + "re-read the current state", Map.of("firstMissed", droppedFrom, "lastMissed", droppedTo,
                            "count", missed));
            droppedFrom = -1;
            droppedTo = -1;
            return notice;
        }

        private void deliver() {
            while (!closed.get()) {
                RuntimeEvent event;
                try {
                    event = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                handle(event);
                RuntimeEvent notice = null;
                synchronized (dropLock) {
                    // Publishing may have stopped while events were dropped: tell the subscriber now.
                    if (droppedFrom >= 0 && queue.isEmpty()) {
                        notice = dropNotice(event);
                    }
                }
                if (notice != null) {
                    handle(notice);
                }
            }
        }

        private void handle(RuntimeEvent event) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException e) {
                LOG.warn("Event listener failed on event {} ({}); continuing", event.sequence(), event.type(), e);
            }
        }

        /**
         * Events this subscriber lost because its queue was full.
         *
         * @return the count
         */
        public long dropped() {
            return dropped.get();
        }

        /** Stops the subscription. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
                Thread t = thread;
                if (t != null) {
                    t.interrupt();
                }
            }
        }
    }
}
