package org.districompute.app.monitoring;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans events out to Server-Sent-Event subscribers. A subscriber follows one experiment or the whole platform;
 * users see their own experiments and cluster events, administrators see everything.
 */
@Component
public class EventHub {

    private static final Logger LOG = LoggerFactory.getLogger(EventHub.class);
    /** Most simultaneous streams; more are refused. */
    static final int MAX_SUBSCRIBERS = 500;

    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    /**
     * A stream.
     *
     * @param emitter the emitter
     * @param experimentId followed experiment, or {@code null} for all visible events
     * @param ownerId the subscribing user's id
     * @param admin whether the user sees everything
     */
    record Subscriber(SseEmitter emitter, String experimentId, Long ownerId, boolean admin) {
    }

    /**
     * Registers a stream.
     *
     * @param experimentId followed experiment, or {@code null}
     * @param ownerId the user's id
     * @param admin whether the user is an administrator
     * @return the emitter, already registered
     */
    public SseEmitter subscribe(String experimentId, Long ownerId, boolean admin) {
        if (subscribers.size() >= MAX_SUBSCRIBERS) {
            throw new IllegalStateException("Too many live streams are open");
        }
        SseEmitter emitter = new SseEmitter(0L);
        Subscriber subscriber = new Subscriber(emitter, experimentId, ownerId, admin);
        subscribers.add(subscriber);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
        return emitter;
    }

    /**
     * Sends an event to every subscriber allowed to see it.
     *
     * @param event the event
     * @param experimentOwnerId owner of the event's experiment, or {@code null} for cluster events
     */
    public void push(EventView event, Long experimentOwnerId) {
        for (Subscriber s : subscribers) {
            if (s.experimentId() != null && !s.experimentId().equals(event.experimentId())) {
                continue;
            }
            if (!s.admin() && experimentOwnerId != null && !experimentOwnerId.equals(s.ownerId())) {
                continue;
            }
            send(s, event);
        }
    }

    /**
     * Sends one event to one stream (replay after reconnection).
     *
     * @param emitter the emitter
     * @param event the event
     * @return {@code false} if the stream is gone
     */
    public boolean replay(SseEmitter emitter, EventView event) {
        try {
            synchronized (emitter) {
                emitter.send(event(event));
            }
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    /** Keeps idle streams open through proxies and detects closed ones. */
    @Scheduled(fixedDelay = 15_000)
    void keepAlive() {
        for (Subscriber s : subscribers) {
            try {
                synchronized (s.emitter()) {
                    s.emitter().send(SseEmitter.event().comment("keep-alive"));
                }
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(s);
            }
        }
    }

    int size() {
        return subscribers.size();
    }

    private void send(Subscriber s, EventView event) {
        try {
            synchronized (s.emitter()) {
                s.emitter().send(event(event));
            }
        } catch (IOException | IllegalStateException e) {
            LOG.debug("Dropping a closed live stream", e);
            subscribers.remove(s);
        }
    }

    private static SseEmitter.SseEventBuilder event(EventView event) {
        SseEmitter.SseEventBuilder builder = SseEmitter.event().name(event.type()).data(event,
                MediaType.APPLICATION_JSON);
        return event.id() == null ? builder : builder.id(String.valueOf(event.id()));
    }
}
