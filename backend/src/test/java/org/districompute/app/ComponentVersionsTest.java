package org.districompute.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.districompute.app.system.ComponentVersions;
import org.junit.jupiter.api.Test;

class ComponentVersionsTest {

    @Test
    void theVersionsComeFromTheIntegratedComponents() {
        ComponentVersions versions = ComponentVersions.current("test");
        assertEquals("0.1.0-SNAPSHOT", versions.core());
        assertEquals("0.1.0-SNAPSHOT", versions.distributed());
        assertEquals("0.1", versions.protocol());
        assertFalse(versions.application().isBlank());
    }
}
