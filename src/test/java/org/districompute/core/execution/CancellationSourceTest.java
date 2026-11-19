package org.districompute.core.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.districompute.core.ErrorCode;
import org.junit.jupiter.api.Test;

class CancellationSourceTest {

    @Test
    void firstReasonWinsAndCancelIsIdempotent() {
        CancellationSource source = new CancellationSource();
        assertFalse(source.isCancelled());
        assertTrue(source.cancel(CancellationReason.TIMEOUT));
        assertFalse(source.cancel(CancellationReason.USER_REQUESTED));
        assertEquals(Optional.of(CancellationReason.TIMEOUT), source.reason());
    }

    @Test
    void throwIfCancelledMapsReasonToErrorCode() {
        CancellationSource timeout = new CancellationSource();
        timeout.cancel(CancellationReason.TIMEOUT);
        assertEquals(ErrorCode.TIMEOUT, assertThrows(TaskCancelledException.class, timeout::throwIfCancelled).code());

        CancellationSource user = new CancellationSource();
        user.cancel(CancellationReason.USER_REQUESTED);
        TaskCancelledException e = assertThrows(TaskCancelledException.class, user::throwIfCancelled);
        assertEquals(ErrorCode.CANCELLED, e.code());
        assertEquals(CancellationReason.USER_REQUESTED, e.reason());
    }

    @Test
    void childFollowsParentButNotViceVersa() {
        CancellationSource parent = new CancellationSource();
        CancellationSource child = parent.newChild();
        child.cancel(CancellationReason.FAILURE);
        assertFalse(parent.isCancelled());

        CancellationSource sibling = parent.newChild();
        parent.cancel(CancellationReason.SHUTDOWN);
        assertEquals(Optional.of(CancellationReason.SHUTDOWN), sibling.reason());
        assertEquals(Optional.of(CancellationReason.FAILURE), child.reason());
    }

    @Test
    void childOfCancelledParentStartsCancelled() {
        CancellationSource parent = new CancellationSource();
        parent.cancel(CancellationReason.USER_REQUESTED);
        assertTrue(parent.newChild().isCancelled());
    }

    @Test
    void callbacksRunExactlyOnce() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.onCancel(calls::incrementAndGet);
        source.cancel(CancellationReason.USER_REQUESTED);
        source.cancel(CancellationReason.USER_REQUESTED);
        source.onCancel(calls::incrementAndGet);
        assertEquals(2, calls.get());
    }

    @Test
    void failingCallbackDoesNotPreventOthers() {
        CancellationSource source = new CancellationSource();
        AtomicInteger calls = new AtomicInteger();
        source.onCancel(() -> {
            throw new IllegalStateException("boom");
        });
        source.onCancel(calls::incrementAndGet);
        source.cancel(CancellationReason.USER_REQUESTED);
        assertEquals(1, calls.get());
    }

    @Test
    void noneTokenIsNeverCancelled() {
        CancellationToken.NONE.throwIfCancelled();
        assertFalse(CancellationToken.NONE.isCancelled());
    }
}
