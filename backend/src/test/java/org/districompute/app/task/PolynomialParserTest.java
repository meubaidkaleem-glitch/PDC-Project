package org.districompute.app.task;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PolynomialParserTest {

    @Test
    void parsesPolynomialsIntoAscendingCoefficients() {
        assertArrayEquals(new double[] {1, -2, 3}, PolynomialParser.parse("3x^2 - 2x + 1", 'x'));
        assertArrayEquals(new double[] {4, 0, 0, -0.5}, PolynomialParser.parse("-0.5*x^3 + 4", 'x'));
        assertArrayEquals(new double[] {-2, 0, 1}, PolynomialParser.parse("x^2-2", 'x'));
        assertArrayEquals(new double[] {0, 2}, PolynomialParser.parse(" x + x ", 'x'), "like terms are summed");
        assertArrayEquals(new double[] {0.0015}, PolynomialParser.parse("1.5e-3", 'x'));
        assertArrayEquals(new double[] {0}, PolynomialParser.parse("x - x", 'x'));
        assertArrayEquals(new double[] {1, 1}, PolynomialParser.parse("T + 1", 't'), "case-insensitive");
        assertEquals(64, PolynomialParser.parse("x^63", 'x').length);
    }

    @Test
    void formatsCoefficientsBack() {
        assertEquals("3x^2 - 2x + 1", PolynomialParser.format(new double[] {1, -2, 3}, 'x'));
        assertEquals("-x^3 + 0.5", PolynomialParser.format(new double[] {0.5, 0, 0, -1}, 'x'));
        assertEquals("0", PolynomialParser.format(new double[] {0}, 'x'));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", "2^x", "x^-1", "x^64", "sin(x)", "x*y", "3 x x", "Runtime.exit(0)", "x^", "1e",
        "(x+1)", "x + + 1", "x;1", "1/x"})
    void rejectsAnythingElse(String expression) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PolynomialParser.parse(expression, 'x'));
        assertTrue(!e.getMessage().isBlank());
    }

    @Test
    void reportsThePositionOfTheError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PolynomialParser.parse("3x^2 - y", 'x'));
        assertTrue(e.getMessage().contains("position 8"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PolynomialParser.parse("x".repeat(2001), 'x'));
    }
}
