package org.districompute.distributed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.districompute.core.CoreVersion;
import org.junit.jupiter.api.Test;

class DistributedVersionTest {

    @Test
    void versionIsFilteredFromTheBuild() {
        String version = DistributedVersion.version();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+(-[A-Za-z0-9.]+)?"), version);
    }

    @Test
    void runtimeAndCoreAreReleasedTogether() {
        // The runtime is built against the Core of the same repository and release line.
        assertEquals(CoreVersion.version(), DistributedVersion.version());
    }
}
