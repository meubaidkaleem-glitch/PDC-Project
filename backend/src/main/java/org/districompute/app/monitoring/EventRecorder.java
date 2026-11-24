package org.districompute.app.monitoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.districompute.app.persistence.ExecutionEvent;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists events in batches on one thread, then pushes them (with their database ids) to live streams. Runtime
 * event delivery therefore never waits for the database. If the queue overflows, events are dropped and counted.
 */
@Component
public class EventRecorder implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(EventRecorder.class);
    private static final int CAPACITY = 50_000;
    private static final int BATCH = 500;

    private final ExecutionEventRepository repository;
    private final TransactionTemplate tx;
    private final EventHub hub;
    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;
    private Thread thread;

    EventRecorder(ExecutionEventRepository repository, TransactionTemplate tx, EventHub hub) {
        this.repository = repository;
        this.tx = tx;
        this.hub = hub;
    }

    /**
     * An event waiting to be stored.
     *
     * @param entity the row
     * @param details parsed details, for the live stream
     * @param ownerId owner of the event's experiment, or {@code null}
     */
    record Pending(ExecutionEvent entity, Map<String, Object> details, Long ownerId) {
    }

    /**
     * Queues an event.
     *
     * @param event the event
     */
    void enqueue(Pending event) {
        if (!queue.offer(event)) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 1000 == 0) {
                LOG.warn("Event queue full; {} events dropped so far", count);
            }
        }
    }

    /**
     * Events dropped because the queue was full.
     *
     * @return the count
     */
    public long dropped() {
        return dropped.get();
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("event-recorder").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush(new ArrayList<>(queue));
        queue.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops after the components that publish events. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void loop() {
        List<Pending> batch = new ArrayList<>(BATCH);
        while (running) {
            try {
                Pending first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.error("Could not store {} events", batch.size(), e);
            } finally {
                batch.clear();
            }
        }
    }

    private void flush(List<Pending> batch) {
        if (batch.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(s -> repository.saveAll(batch.stream().map(Pending::entity).toList()));
        for (Pending p : batch) {
            hub.push(EventView.of(p.entity(), p.details()), p.ownerId());
        }
    }
}
