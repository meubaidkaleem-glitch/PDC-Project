package org.districompute.app;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The integration tests with the standalone profile: the embedded H2 database in the data directory with its own
 * migrations, as in the Windows and Linux packages ({@code docs/standalone.md}).
 */
@ActiveProfiles({"standalone", "test"})
class ApplicationStandaloneTest extends ApplicationIntegrationTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("districompute.data-dir", () -> newDataDirectory("standalone"));
    }
}
