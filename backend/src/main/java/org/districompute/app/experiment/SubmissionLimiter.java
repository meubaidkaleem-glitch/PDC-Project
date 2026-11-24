package org.districompute.app.experiment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.districompute.app.api.ApiException;
import org.districompute.app.config.AppProperties;
import org.springframework.stereotype.Component;

/** Per-user sliding-window limit on experiment creation ({@code districompute.limits.submissions-per-minute}). */
@Component
public class SubmissionLimiter {

    private final int perMinute;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> windows = new ConcurrentHashMap<>();

    SubmissionLimiter(AppProperties properties, Clock clock) {
        this.perMinute = properties.limits().submissionsPerMinute();
        this.clock = clock;
    }

    /**
     * Counts submissions and refuses those beyond the limit.
     *
     * @param userId the user
     * @param count submissions requested at once
     * @throws ApiException 429 when the limit is reached
     */
    public void acquire(Long userId, int count) {
        Instant now = clock.instant();
        Deque<Instant> window = windows.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && window.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                window.pollFirst();
            }
            if (window.size() + count > perMinute) {
                throw ApiException.tooManyRequests("At most " + perMinute + " experiments per minute; try again "
                        + "shortly");
            }
            for (int i = 0; i < count; i++) {
                window.addLast(now);
            }
        }
    }
}
