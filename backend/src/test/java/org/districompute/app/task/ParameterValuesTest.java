package org.districompute.app.task;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ParameterValuesTest {

    @Test
    void convertsJsonNumbersIntoTheTypesCoreAccepts() {
        Map<String, Object> out = ParameterValues.normalize(Map.of("a", 5, "b", 2.5f, "c", new BigDecimal("7"),
                "d", new BigDecimal("1.25"), "e", List.of(1, 2.0, new BigDecimal("3")), "f", "TEXT",
                "g", BigInteger.TEN));
        assertEquals(5L, out.get("a"));
        assertEquals(2.5, out.get("b"));
        assertEquals(7L, out.get("c"));
        assertEquals(1.25, out.get("d"));
        assertEquals(List.of(1L, 2.0, 3L), out.get("e"));
        assertEquals("TEXT", out.get("f"));
        assertEquals(10L, out.get("g"));
        assertEquals(3, ParameterValues.largestArray(out));
    }
}
