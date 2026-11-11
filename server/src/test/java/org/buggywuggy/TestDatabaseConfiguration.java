package org.buggywuggy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides the test database.
 *
 * <p>By default a PostgreSQL 16 container is started with Testcontainers (requires Docker).
 * Set {@code BUGGYWUGGY_TEST_TESTCONTAINERS=false} to use the database configured through
 * {@code BUGGYWUGGY_DB_URL}, {@code BUGGYWUGGY_DB_USER} and {@code BUGGYWUGGY_DB_PASSWORD} instead.
 * Use a dedicated, disposable test database for that: tests may modify its contents.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(name = "buggywuggy.test.testcontainers", havingValue = "true", matchIfMissing = true)
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
