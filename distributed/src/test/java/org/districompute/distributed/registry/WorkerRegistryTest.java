package org.districompute.distributed.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.distributed.protocol.HeartbeatPayload;
import org.districompute.distributed.testing.CoreFixtures;
import org.junit.jupiter.api.Test;

class WorkerRegistryTest {

    private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");

    private final WorkerRegistry registry = new WorkerRegistry(2, 1);

    @Test
    void registrationRules() {
        assertInstanceOf(WorkerRegistry.Accepted.class, register("a"));
        WorkerRegistry.Registration duplicate = register("a");
        assertTrue(((WorkerRegistry.Rejected) duplicate).reason().contains("already registered"));
        assertInstanceOf(WorkerRegistry.Accepted.class, register("b"));
        assertTrue(((WorkerRegistry.Rejected) register("c")).reason().contains("at most 2 workers"));
        assertEquals(2, registry.connectedCount());
    }

    @Test
    void slotsAreAssignedAndReleased() {
        WorkerRecord a = accepted(register("a"));
        assertEquals(3, a.freeSlots());
        a.assign("t/p0/a1", 10);
        a.assign("t/p1/a1", 20);
        a.assign("t/p1/a1", 30);
        assertEquals(1, a.freeSlots(), "assigning the same attempt twice holds one slot");
        assertTrue(a.release("t/p0/a1", 40));
        assertFalse(a.release("t/p0/a1", 50));
        assertTrue(a.release("t/p1/a1", 60));
        assertEquals(60, a.idleSinceNanos(), "idle since the last slot was freed");
        assertEquals(2, a.snapshot().counters().assigned());
    }

    @Test
    void disconnectionReturnsAssignedAttemptsAndReconnectionKeepsHistory() {
        WorkerRecord a = accepted(register("a"));
        a.assign("t/p0/a1", 1);
        a.count(AttemptOutcome.SUCCEEDED);
        a.history().observe(AlgorithmId.of("primes.count"), 1e6, 1_000_000, 1000, 1000);
        assertEquals(List.of("t/p0/a1"), registry.disconnect("a", "connection closed", T0));
        assertEquals(WorkerStatus.DISCONNECTED, a.status());
        assertNull(a.sessionId());
        assertEquals(List.of(), registry.disconnect("a", "again", T0), "idempotent");

        WorkerRegistry.Accepted again = (WorkerRegistry.Accepted) register("a");
        assertTrue(again.reconnection());
        assertEquals(a, again.record());
        WorkerSnapshot snapshot = again.record().snapshot();
        assertEquals(2, snapshot.registrations());
        assertEquals(1, snapshot.counters().succeeded());
        assertEquals(1e9, snapshot.performance().get(0).operationsPerSecond(), 1e-3);
        assertEquals(0, snapshot.assignedTasks());
    }

    @Test
    void onlyARecentDisconnectedRecordIsRetained() {
        register("a");
        register("b");
        registry.disconnect("a", "gone", T0);
        registry.disconnect("b", "gone", T0.plusSeconds(1));
        assertEquals(List.of("b"), registry.all().stream().map(WorkerRecord::workerId).toList());
    }

    @Test
    void heartbeatsMustAdvanceAndUpdateCapacity() {
        WorkerRecord a = accepted(register("a"));
        assertTrue(a.heartbeat(heartbeat(2, 5), T0, 100));
        assertFalse(a.heartbeat(heartbeat(2, 1), T0, 200), "a repeated sequence is stale");
        assertFalse(a.heartbeat(heartbeat(1, 1), T0, 300), "an older sequence is stale");
        assertEquals(5, a.maxConcurrentTasks());
        assertEquals(100, a.lastHeartbeatNanos());
    }

    @Test
    void statusChangesReportThePreviousStatus() {
        WorkerRecord a = accepted(register("a"));
        assertEquals(WorkerStatus.ACTIVE, a.setStatus(WorkerStatus.SUSPECT, "late", T0));
        assertNull(a.setStatus(WorkerStatus.SUSPECT, "still late", T0));
        assertEquals("late", a.snapshot().statusReason());
    }

    @Test
    void performanceHistoryUsesMovingAverages() {
        PerformanceHistory history = new PerformanceHistory();
        AlgorithmId id = AlgorithmId.of("matrix.multiply");
        history.observe(id, 1000, 1_000_000_000L, 2000, 1_000_000_000L);
        history.observe(id, 2000, 1_000_000_000L, 4000, 1_000_000_000L);
        PerformanceStats stats = history.stats(id).orElseThrow();
        assertEquals(2, stats.samples());
        assertEquals(0.3 * 2000 + 0.7 * 1000, stats.operationsPerSecond(), 1e-9);
        assertEquals(0.3 * 4000 + 0.7 * 2000, stats.transferBytesPerSecond(), 1e-9);
        assertEquals(1000, stats.meanKernelMillis(), 1e-9);
        assertTrue(history.stats(AlgorithmId.of("sort.merge")).isEmpty());
    }

    private WorkerRegistry.Registration register(String id) {
        return registry.register(id, "s-" + id, CoreFixtures.capabilities(3), "127.0.0.1:1", T0, 0);
    }

    private static WorkerRecord accepted(WorkerRegistry.Registration registration) {
        return ((WorkerRegistry.Accepted) registration).record();
    }

    private static HeartbeatPayload heartbeat(long sequence, int capacity) {
        return new HeartbeatPayload(sequence, 0, capacity, 1, -1, -1, 0, 0, List.of());
    }
}
