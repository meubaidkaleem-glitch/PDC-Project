package org.districompute.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.junit.jupiter.api.Test;

class TaskStatusTest {

    private static final List<TaskStatus> FORWARD_PATH = List.of(TaskStatus.CREATED, TaskStatus.VALIDATING,
            TaskStatus.VALIDATED, TaskStatus.PLANNED, TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.AGGREGATING,
            TaskStatus.VERIFYING, TaskStatus.COMPLETED);

    @Test
    void permitsExactlyTheDocumentedTransitions() {
        for (TaskStatus from : TaskStatus.values()) {
            Set<TaskStatus> expected = EnumSet.noneOf(TaskStatus.class);
            if (!from.isTerminal()) {
                expected.add(TaskStatus.FAILED);
                expected.add(TaskStatus.CANCELLED);
                expected.add(FORWARD_PATH.get(FORWARD_PATH.indexOf(from) + 1));
            }
            for (TaskStatus to : TaskStatus.values()) {
                assertEquals(expected.contains(to), from.canTransitionTo(to), from + " -> " + to);
            }
        }
    }

    @Test
    void completedIsOnlyReachableFromVerifying() {
        for (TaskStatus from : TaskStatus.values()) {
            assertEquals(from == TaskStatus.VERIFYING, from.canTransitionTo(TaskStatus.COMPLETED), from.name());
        }
    }

    @Test
    void terminalStatusesAreFinal() {
        assertTrue(TaskStatus.COMPLETED.isTerminal());
        assertTrue(TaskStatus.FAILED.isTerminal());
        assertTrue(TaskStatus.CANCELLED.isTerminal());
        assertFalse(TaskStatus.VERIFYING.isTerminal());
    }

    @Test
    void lifecycleRecordsHistoryAlongTheForwardPath() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC);
        TaskLifecycle lifecycle = new TaskLifecycle(clock);
        for (TaskStatus status : FORWARD_PATH.subList(1, FORWARD_PATH.size())) {
            lifecycle.transitionTo(status);
        }
        assertEquals(TaskStatus.COMPLETED, lifecycle.current());
        assertEquals(FORWARD_PATH, lifecycle.history().stream().map(StatusChange::status).toList());
        assertEquals(Instant.parse("2026-11-19T10:00:00Z"), lifecycle.history().get(0).at());
    }

    @Test
    void lifecycleRejectsSkippingVerification() {
        TaskLifecycle lifecycle = new TaskLifecycle();
        lifecycle.transitionTo(TaskStatus.VALIDATING);
        lifecycle.transitionTo(TaskStatus.VALIDATED);
        lifecycle.transitionTo(TaskStatus.PLANNED);
        lifecycle.transitionTo(TaskStatus.QUEUED);
        lifecycle.transitionTo(TaskStatus.RUNNING);
        DistriComputeException e = assertThrows(DistriComputeException.class,
                () -> lifecycle.transitionTo(TaskStatus.COMPLETED));
        assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code());
        assertEquals(TaskStatus.RUNNING, lifecycle.current());
    }

    @Test
    void lifecycleIgnoresLateTransitionsAfterTermination() {
        TaskLifecycle lifecycle = new TaskLifecycle();
        lifecycle.transitionTo(TaskStatus.CANCELLED);
        assertFalse(lifecycle.tryTransitionTo(TaskStatus.FAILED));
        assertFalse(lifecycle.tryTransitionTo(TaskStatus.VALIDATING));
        assertEquals(2, lifecycle.history().size());
    }
}
