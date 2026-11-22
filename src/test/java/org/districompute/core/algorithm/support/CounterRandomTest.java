package org.districompute.core.algorithm.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CounterRandomTest {

    @Test
    void matchesPublishedSplitMix64VectorsForSeedZero() {
        // The documented formula with stream 0 reproduces the reference SplitMix64 sequence for seed 0.
        assertEquals(0xE220A8397B1DCDAFL, CounterRandom.longAt(0, 0, 0));
        assertEquals(0x6E789E6AA1B965F4L, CounterRandom.longAt(0, 0, 1));
        assertEquals(0x06C45D188009454FL, CounterRandom.longAt(0, 0, 2));
    }

    @Test
    void isAPureFunctionOfItsArguments() {
        assertEquals(CounterRandom.longAt(42, 1, 1000), CounterRandom.longAt(42, 1, 1000));
        assertNotEquals(CounterRandom.longAt(42, 1, 1000), CounterRandom.longAt(42, 2, 1000));
        assertNotEquals(CounterRandom.longAt(42, 1, 1000), CounterRandom.longAt(43, 1, 1000));
    }

    @Test
    void derivedValuesStayInTheirRanges() {
        for (long i = 0; i < 100_000; i++) {
            double unit = CounterRandom.unitAt(7, 3, i);
            double symmetric = CounterRandom.symmetricAt(7, 3, i);
            int bounded = CounterRandom.boundedAt(7, 3, i, 17);
            assertTrue(unit >= 0 && unit < 1);
            assertTrue(symmetric >= -1 && symmetric < 1);
            assertTrue(bounded >= 0 && bounded < 17);
        }
        assertThrows(IllegalArgumentException.class, () -> CounterRandom.boundedAt(1, 1, 1, 0));
    }

    @Test
    void sizesSaturateInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, Sizes.saturatedProduct(Long.MAX_VALUE / 2, 3));
        assertEquals(Long.MAX_VALUE, Sizes.saturatedSum(Long.MAX_VALUE, 1));
        assertEquals(24, Sizes.wordBytes(3));
        assertTrue(Sizes.fitsInArray(Sizes.MAX_ARRAY_LENGTH));
        assertTrue(!Sizes.fitsInArray(Sizes.MAX_ARRAY_LENGTH + 1));
    }
}
