package org.districompute.app;

import org.districompute.app.testing.TestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The integration tests against MySQL 8.4 in a container, the database of a server installation. */
@ActiveProfiles("test")
@Import(TestDatabase.class)
class ApplicationOnMysqlTest extends ApplicationIntegrationTest {

    @DynamicPropertySource
    static void dataDirectory(DynamicPropertyRegistry registry) {
        registry.add("districompute.data-dir", () -> newDataDirectory("mysql"));
    }
}
