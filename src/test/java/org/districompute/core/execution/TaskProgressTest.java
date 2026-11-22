package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.districompute.core.task.TaskStatus;
import org.junit.jupiter.api.Test;

class TaskProgressTest {

    @Test
    void throttlesAdvancesButAlwaysEmitsStatusChanges() {
        List<ProgressEvent> events = new ArrayList<>();
        TaskProgress progress = new TaskProgress("t", events::add, 60_000, Clock.systemUTC());
        progress.status(TaskStatus.RUNNING);
        progress.start(100, "rows");
        for (int i = 0; i < 100; i++) {
            progress.advance(1);
        }
        progress.status(TaskStatus.AGGREGATING);
        assertEquals(List.of(TaskStatus.RUNNING, TaskStatus.AGGREGATING),
                events.stream().map(ProgressEvent::status).toList(), "advances are throttled away");
        assertEquals(100, events.get(1).completedUnits());
        assertEquals("rows", events.get(1).unit());
    }

    @Test
    void eventsAreMonotonicAndClampedToTheTotal() {
        List<ProgressEvent> events = new ArrayList<>();
        TaskProgress progress = new TaskProgress("t", events::add, 0, Clock.systemUTC());
        progress.status(TaskStatus.RUNNING);
        progress.start(10, "items");
        for (int i = 0; i < 25; i++) {
            progress.advance(1);
        }
        progress.status(TaskStatus.COMPLETED);
        long previousSequence = 0;
        long previousUnits = 0;
        for (ProgressEvent event : events) {
            assertTrue(event.sequence() > previousSequence);
            assertTrue(event.completedUnits() >= previousUnits);
            assertTrue(event.completedUnits() <= 10);
            previousSequence = event.sequence();
            previousUnits = event.completedUnits();
        }
        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(TaskStatus.COMPLETED, last.status());
        assertEquals(10, last.completedUnits());
    }

    @Test
    void completionFillsTheTotalEvenIfKernelsReportedLess() {
        List<ProgressEvent> events = new ArrayList<>();
        TaskProgress progress = new TaskProgress("t", events::add, 0, Clock.systemUTC());
        progress.declareTotal(1_000, "integers", 4);
        progress.advance(10);
        progress.partitionCompleted();
        progress.status(TaskStatus.COMPLETED);
        ProgressEvent last = events.get(events.size() - 1);
        assertEquals(1_000, last.completedUnits());
        assertEquals(4, last.completedPartitions());
        assertEquals(4, last.totalPartitions());
    }

    @Test
    void engineTotalIsNotOverwrittenByKernels() {
        List<ProgressEvent> events = new ArrayList<>();
        TaskProgress progress = new TaskProgress("t", events::add, 0, Clock.systemUTC());
        progress.declareTotal(500, "rows", 2);
        progress.start(50, "rows-of-one-partition");
        progress.status(TaskStatus.RUNNING);
        assertEquals(500, events.get(0).totalUnits());
        assertEquals("rows", events.get(0).unit());
    }

    @Test
    void failingListenerNeverBreaksProgressReporting() {
        TaskProgress progress = new TaskProgress("t", event -> {
            throw new IllegalStateException("listener bug");
        }, 0, Clock.systemUTC());
        progress.status(TaskStatus.RUNNING);
        progress.advance(5);
        progress.status(TaskStatus.COMPLETED);
    }
}
