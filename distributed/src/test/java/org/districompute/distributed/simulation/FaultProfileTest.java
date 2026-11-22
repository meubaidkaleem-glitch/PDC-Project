package org.districompute.distributed.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class FaultProfileTest {

    @Test
    void parsesEveryKey() {
        Properties p = new Properties();
        p.setProperty("enabled", "true");
        p.setProperty("seed", "7");
        p.setProperty("slowdown", "3.5");
        p.setProperty("message-latency-ms", "20");
        p.setProperty("response-delay-ms", "5");
        p.setProperty("disconnect-after-tasks", "4");
        p.setProperty("stop-heartbeats-after-ms", "1000");
        p.setProperty("hang-probability", "0.1");
        p.setProperty("fail-probability", "0.2");
        p.setProperty("corrupt-probability", "0.05");
        p.setProperty("duplicate-probability", "0.05");
        p.setProperty("capacity-schedule", "1000:1, 5000:4");
        FaultProfile profile = FaultProfile.of(p);
        assertTrue(profile.enabled());
        assertEquals(3.5, profile.slowdownFactor());
        assertEquals(List.of(new FaultProfile.CapacityChange(1000, 1), new FaultProfile.CapacityChange(5000, 4)),
                profile.capacitySchedule());
        assertTrue(profile.describe().contains("slowdown x3.5"), profile.describe());
        assertEquals("none", FaultProfile.NONE.describe());
    }

    @Test
    void rejectsUnknownKeysAndInvalidValues() {
        Properties typo = new Properties();
        typo.setProperty("slowdwn", "2");
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.of(typo));
        Properties bad = new Properties();
        bad.setProperty("fail-probability", "1.5");
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.of(bad));
        Properties slow = new Properties();
        slow.setProperty("slowdown", "0.5");
        assertThrows(IllegalArgumentException.class, () -> FaultProfile.of(slow), "speed-ups are not faults");
    }

    @Test
    void behaviourIsDeterministicPerSeedAndAttempt() {
        FaultProfile profile = new FaultProfile(true, 42, 0, 0, 1, 0, 0, 0.25, 0.25, 0.25, 0.25, List.of());
        FaultInjector a = new FaultInjector(profile);
        FaultInjector b = new FaultInjector(profile);
        for (int i = 0; i < 50; i++) {
            assertEquals(a.behaviour("t/p" + i + "/a1"), b.behaviour("t/p" + i + "/a1"));
        }
        long hangs = java.util.stream.IntStream.range(0, 400)
                .filter(i -> a.behaviour("t/p" + i + "/a1") == FaultInjector.Behaviour.HANG).count();
        assertTrue(hangs > 60 && hangs < 140, "about a quarter, got " + hangs);
        FaultInjector off = new FaultInjector(FaultProfile.NONE);
        assertEquals(FaultInjector.Behaviour.NORMAL, off.behaviour("x/p0/a1"));
        assertFalse(off.heartbeatsStopped(1_000_000));
        assertEquals(0, off.extraKernelNanos(1000));
    }
}
