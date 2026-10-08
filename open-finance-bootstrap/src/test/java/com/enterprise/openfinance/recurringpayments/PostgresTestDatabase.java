package com.enterprise.openfinance.recurringpayments;

import org.junit.jupiter.api.Assumptions;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL /
 * TEST_DB_USERNAME / TEST_DB_PASSWORD (service container in
 * required-gates.yml) and the tests FAIL there if it is missing (CI=true).
 * Locally set TEST_DB_URL or run Docker for Testcontainers; with neither the
 * tests are skipped.
 */
final class PostgresTestDatabase {

    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() {
    }

    /** Call from a static @BeforeAll. */
    static void assumeAvailable() {
        if (hasExternalDatabase()) {
            return;
        }
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            throw new IllegalStateException("CI=true but TEST_DB_URL is not set: integration tests must not be skipped in CI");
        }
        Assumptions.assumeTrue(dockerAvailable(), "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean hasExternalDatabase() {
        String url = System.getenv("TEST_DB_URL");
        return url != null && !url.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        if (hasExternalDatabase()) {
            registry.add("spring.datasource.url", () -> System.getenv("TEST_DB_URL"));
            registry.add("spring.datasource.username", () -> env("TEST_DB_USERNAME", "mandates_test"));
            registry.add("spring.datasource.password", () -> env("TEST_DB_PASSWORD", "mandates_test"));
            return;
        }
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("db_pay_recurring_mandates_test")
                    .withUsername("mandates_test")
                    .withPassword("mandates_test");
            container.start();
        }
        registry.add("spring.datasource.url", container::getJdbcUrl);
        registry.add("spring.datasource.username", container::getUsername);
        registry.add("spring.datasource.password", container::getPassword);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
