package com.finora;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for tests that need a real Postgres, not H2 or a mock. Soft-delete behavior
 * (@SQLRestriction), JSONB columns (audit_logs.metadata), and array columns (transactions.tags)
 * all depend on real Postgres semantics that an in-memory database won't faithfully reproduce —
 * this is exactly the class of bug Testcontainers exists to catch that a mocked repository can't.
 *
 * One container is shared across all test classes that extend this (via the static @Container
 * field + Testcontainers' reuse), so the full suite doesn't pay container-startup cost per class.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // @SuppressWarnings("resource"): never explicitly closed by design, not an oversight -- see the
    // class doc comment above. Testcontainers' reuse keeps this one container alive across every
    // test class that extends this base; closing it here would defeat that (forcing a fresh
    // container per class again).
    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finora_test")
            .withUsername("finora")
            .withPassword("finora");

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway runs against the real containerized Postgres exactly as it would in production —
        // this is what makes the soft-delete / JSONB / array-column tests meaningful.
    }
}
