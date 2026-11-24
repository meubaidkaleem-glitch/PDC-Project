package org.districompute.app.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.districompute.app.api.ApiException;
import org.junit.jupiter.api.Test;

class InputParsingTest {

    @Test
    void readsCommaSemicolonTabAndSpaceSeparatedNumbersWithAnOptionalHeader() {
        assertEquals(6, InputStore.parse("a,b,c\n1,2,3\r\n4;5;6\n", false, 100).count());
        var parsed = InputStore.parse("# comment\n1\t2\n\n3 4\n", true, 100);
        assertEquals(4, parsed.count());
        assertEquals(2, parsed.rows());
        assertEquals(2, parsed.columns());
        assertEquals(4L, parsed.longs()[3]);
    }

    @Test
    void rejectsRaggedRowsNonNumbersAndTooManyValues() {
        assertEquals("RAGGED_ROWS", code(() -> InputStore.parse("1,2\n3\n", false, 100)));
        assertEquals("NOT_A_NUMBER", code(() -> InputStore.parse("1,x\n", false, 100)));
        assertEquals("NOT_A_NUMBER", code(() -> InputStore.parse("1.5\n", true, 100)), "integers only");
        assertEquals("NOT_A_NUMBER", code(() -> InputStore.parse("1,NaN\n", false, 100)), "finite only");
        assertEquals("TOO_MANY_VALUES", code(() -> InputStore.parse("1,2,3\n", false, 2)));
        assertEquals("NO_VALUES", code(() -> InputStore.parse("header\n", false, 2)));
    }

    private static String code(Runnable action) {
        return assertThrows(ApiException.class, action::run).code();
    }
}
