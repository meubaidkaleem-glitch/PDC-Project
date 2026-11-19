package org.districompute.core.algorithm;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class AlgorithmIdentityTest {

    @ParameterizedTest
    @ValueSource(strings = {"matrix.multiply", "primes.count", "numeric.root-find", "a1.b2.c3"})
    void acceptsValidIds(String id) {
        assertDoesNotThrow(() -> AlgorithmId.of(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "matrix", "Matrix.multiply", "matrix.", ".multiply", "matrix..multiply",
        "matrix.multiply!", "1matrix.multiply", "matrix.-multiply"})
    void rejectsInvalidIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> AlgorithmId.of(id));
    }

    @Test
    void parsesAndPrintsVersions() {
        AlgorithmVersion version = AlgorithmVersion.parse("1.10.3");
        assertEquals(new AlgorithmVersion(1, 10, 3), version);
        assertEquals("1.10.3", version.toString());
        assertThrows(IllegalArgumentException.class, () -> AlgorithmVersion.parse("1.0"));
        assertThrows(IllegalArgumentException.class, () -> AlgorithmVersion.parse("01.0.0"));
        assertThrows(IllegalArgumentException.class, () -> AlgorithmVersion.parse("1.0.0-beta"));
    }

    @Test
    void compatibilityRequiresSameMajorAndAtLeastRequestedMinor() {
        AlgorithmVersion registered = AlgorithmVersion.parse("1.2.0");
        assertTrue(registered.satisfies(AlgorithmVersion.parse("1.0.0")));
        assertTrue(registered.satisfies(AlgorithmVersion.parse("1.2.9")));
        assertFalse(registered.satisfies(AlgorithmVersion.parse("1.3.0")));
        assertFalse(registered.satisfies(AlgorithmVersion.parse("2.0.0")));
        assertFalse(registered.satisfies(AlgorithmVersion.parse("0.2.0")));
    }

    @Test
    void versionsOrderSemantically() {
        assertTrue(AlgorithmVersion.parse("1.10.0").compareTo(AlgorithmVersion.parse("1.9.9")) > 0);
        assertTrue(AlgorithmVersion.parse("2.0.0").compareTo(AlgorithmVersion.parse("1.99.99")) > 0);
    }
}
