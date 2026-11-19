package org.districompute.core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CoreVersionTest {

    @Test
    void versionIsFilteredFromTheBuild() {
        String version = CoreVersion.version();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), "unexpected version: " + version);
    }
}
