package org.districompute.distributed.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void attemptIdsExtendCorePartitionIds() {
        assertEquals("task-1/p3/a2", Ids.attemptId("task-1/p3", 2));
        assertEquals(2, Ids.attemptNumber("task-1/p3", "task-1/p3/a2"));
        assertEquals(-1, Ids.attemptNumber("task-1/p3", "task-1/p4/a2"));
        assertEquals(-1, Ids.attemptNumber("task-1/p3", "task-1/p3/a0"));
        assertEquals(-1, Ids.attemptNumber("task-1/p3", "task-1/p3/a"));
        assertThrows(IllegalArgumentException.class, () -> Ids.attemptId("task-1/p3", 0));
    }

    @Test
    void partitionIdsMustBelongToTheirTask() {
        assertTrue(Ids.isPartitionOf("t", "t/p0"));
        assertTrue(Ids.isPartitionOf("t", "t/p15"));
        assertFalse(Ids.isPartitionOf("t", "t/p01"));
        assertFalse(Ids.isPartitionOf("t", "tt/p1"));
        assertFalse(Ids.isPartitionOf("t", "t/x1"));
        assertFalse(Ids.isPartitionOf(null, "t/p1"));
    }

    @Test
    void workerIdsAreRestricted() {
        assertTrue(Ids.matches(Ids.WORKER_ID, "lab-pc-07.local:2"));
        assertFalse(Ids.matches(Ids.WORKER_ID, "has space"));
        assertFalse(Ids.matches(Ids.WORKER_ID, "x".repeat(65)));
        assertFalse(Ids.matches(Ids.WORKER_ID, null));
    }

    @Test
    void duplicateFilterRemembersABoundedWindow() {
        DuplicateMessageFilter filter = new DuplicateMessageFilter(2);
        assertTrue(filter.firstSeen("a"));
        assertFalse(filter.firstSeen("a"));
        assertTrue(filter.firstSeen("b"));
        assertTrue(filter.firstSeen("c"));
        assertTrue(filter.firstSeen("a"), "the oldest id was forgotten");
        assertThrows(IllegalArgumentException.class, () -> new DuplicateMessageFilter(0));
    }

    @Test
    void protocolVersionRules() {
        assertTrue(ProtocolVersion.isSupported("0.1"));
        assertFalse(ProtocolVersion.isSupported("0.2"));
        assertFalse(ProtocolVersion.isSupported("1.1"));
        assertFalse(ProtocolVersion.isSupported("01.1"));
        assertFalse(ProtocolVersion.isSupported(null));
    }
}
