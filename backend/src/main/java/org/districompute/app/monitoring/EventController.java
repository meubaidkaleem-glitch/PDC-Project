package org.districompute.app.monitoring;

import java.util.List;
import org.districompute.app.config.JsonSupport;
import org.districompute.app.persistence.AppUser;
import org.districompute.app.persistence.ExecutionEventRepository;
import org.districompute.app.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Platform-wide events: recent history and a live stream ({@code /api/v1/events}). */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final ExecutionEventRepository events;
    private final EventHub hub;
    private final ExperimentDirectory directory;
    private final CurrentUser currentUser;
    private final JsonSupport json;

    EventController(ExecutionEventRepository events, EventHub hub, ExperimentDirectory directory,
            CurrentUser currentUser, JsonSupport json) {
        this.events = events;
        this.hub = hub;
        this.directory = directory;
        this.currentUser = currentUser;
        this.json = json;
    }

    /**
     * Recent events the user may see: cluster events and events of the user's experiments (all for administrators).
     *
     * @param limit at most this many (at most 500)
     * @return events, newest first
     */
    @GetMapping
    public List<EventView> recent(@RequestParam(defaultValue = "100") int limit) {
        AppUser user = currentUser.require();
        boolean admin = currentUser.isAdmin();
        return events.findBySimulatedFalseOrderByIdDesc(PageRequest.of(0, Math.min(500, Math.max(1, limit)) * 3)).stream()
                .filter(e -> admin || e.getExperimentId() == null
                        || directory.owner(e.getExperimentId()).map(user.getId()::equals).orElse(false))
                .limit(Math.min(500, Math.max(1, limit))).map(e -> EventView.of(e, json.map(e.getDetails()))).toList();
    }

    /**
     * Live events the user may see (Server-Sent Events).
     *
     * @return the stream
     */
    @GetMapping(path = "/stream", produces = "text/event-stream")
    public SseEmitter stream() {
        return hub.subscribe(null, currentUser.require().getId(), currentUser.isAdmin());
    }
}
