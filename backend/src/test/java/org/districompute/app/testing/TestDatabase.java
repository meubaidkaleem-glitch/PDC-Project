package org.districompute.app.testing;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real MySQL 8.4 for tests (Testcontainers; Docker is required). Spring's context cache shares one container
 * between test classes with the same configuration.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabase {

    /** Image used by tests, the Docker Compose file and CI. */
    public static final String IMAGE = "mysql:8.4";

    @Bean
    @ServiceConnection
    MySQLContainer mysql() {
        return new MySQLContainer(DockerImageName.parse(IMAGE)).withDatabaseName("districompute")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");
    }
}
