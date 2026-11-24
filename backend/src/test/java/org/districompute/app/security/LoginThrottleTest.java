package org.districompute.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    /** A clock the test moves forward. */
    private static final class Manual extends Clock {
        private Instant now = Instant.parse("2026-11-24T10:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void blocksAfterFiveFailuresUntilTheOldestLeavesTheWindow() {
        Manual clock = new Manual();
        LoginThrottle throttle = new LoginThrottle(clock);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) {
            assertEquals(Duration.ZERO, throttle.retryAfter("Mallory", "10.0.0.1"));
            throttle.failed("mallory", "10.0.0.1");
            clock.now = clock.now.plusSeconds(60);
        }
        Duration wait = throttle.retryAfter("MALLORY ", "10.0.0.1");
        assertEquals(Duration.ofMinutes(10), wait, "names are compared without case; the first failure expires at 15 min");
        assertEquals(Duration.ZERO, throttle.retryAfter("mallory", "10.0.0.2"), "another address is not blocked");
        assertEquals(Duration.ZERO, throttle.retryAfter("alice", "10.0.0.1"), "another user is not blocked");

        clock.now = clock.now.plus(wait);
        assertEquals(Duration.ZERO, throttle.retryAfter("mallory", "10.0.0.1"));
    }

    @Test
    void aSuccessfulSignInForgetsTheFailures() {
        LoginThrottle throttle = new LoginThrottle(new Manual());
        for (int i = 0; i < LoginThrottle.MAX_FAILURES - 1; i++) {
            throttle.failed("alice", "10.0.0.1");
        }
        throttle.succeeded("alice", "10.0.0.1");
        throttle.failed("alice", "10.0.0.1");
        assertTrue(throttle.retryAfter("alice", "10.0.0.1").isZero());
    }
}
