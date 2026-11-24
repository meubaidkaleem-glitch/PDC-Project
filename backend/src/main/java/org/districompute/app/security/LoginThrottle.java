package org.districompute.app.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing ({@code docs/security.md}): after {@value #MAX_FAILURES} failed sign-ins for one user
 * name from one address within {@link #WINDOW}, further attempts from there are refused until the oldest failure
 * leaves the window. Keyed by name and address, so that guessing from one place does not lock the user out
 * everywhere. Kept in memory; a restart forgets it.
 */
@Component
public class LoginThrottle {

    /** Failed attempts allowed in the window. */
    public static final int MAX_FAILURES = 5;
    /** The window. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int MAX_KEYS = 10_000;

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * How long the caller must wait before trying again.
     *
     * @param username user name tried
     * @param address client address
     * @return zero when an attempt is allowed
     */
    public Duration retryAfter(String username, String address) {
        Deque<Instant> recent = failures.get(key(username, address));
        if (recent == null) {
            return Duration.ZERO;
        }
        synchronized (recent) {
            prune(recent);
            if (recent.size() < MAX_FAILURES) {
                return Duration.ZERO;
            }
            return Duration.between(clock.instant(), recent.peekFirst().plus(WINDOW));
        }
    }

    /**
     * Records a failed attempt.
     *
     * @param username user name tried
     * @param address client address
     */
    public void failed(String username, String address) {
        if (failures.size() >= MAX_KEYS) {
            failures.values().removeIf(d -> {
                synchronized (d) {
                    prune(d);
                    return d.isEmpty();
                }
            });
        }
        Deque<Instant> recent = failures.computeIfAbsent(key(username, address), k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    /**
     * Forgets the failures after a successful sign-in.
     *
     * @param username user name
     * @param address client address
     */
    public void succeeded(String username, String address) {
        failures.remove(key(username, address));
    }

    private void prune(Deque<Instant> recent) {
        Instant limit = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(limit)) {
            recent.removeFirst();
        }
    }

    private static String key(String username, String address) {
        return (username == null ? "" : username.strip().toLowerCase(Locale.ROOT)) + "|" + address;
    }
}
