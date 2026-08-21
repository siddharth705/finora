package com.finora;

import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real Postgres, not H2 or a mock. Soft-delete behavior
 * (@SQLRestriction), JSONB columns (audit_logs.metadata), and array columns (transactions.tags)
 * all depend on real Postgres semantics that an in-memory database won't faithfully reproduce —
 * this is exactly the class of bug Testcontainers exists to catch that a mocked repository can't.
 *
 * <p>One container is shared across every test class that extends this, started once per JVM and
 * deliberately never stopped. This is the "singleton container" pattern, and it is written out by
 * hand below rather than delegated to {@code @Testcontainers}/{@code @Container} for a reason.
 *
 * <p><b>Bug fix.</b> This class previously carried {@code @Testcontainers} with a static
 * {@code @Container} field, and a doc comment claiming "Testcontainers' reuse keeps this one
 * container alive across every test class". It did not. Reuse requires {@code .withReuse(true)}
 * plus {@code testcontainers.reuse.enable=true}, and neither was ever set. What
 * {@code @Testcontainers} actually does with a static field is start the container before each
 * test class and <em>stop it after that class finishes</em>.
 *
 * <p>That is fatal in combination with Spring's context cache. Class A starts the container on
 * port X, {@code @DynamicPropertySource} bakes port X into the context, and the context is cached.
 * Class A ends and the container is destroyed. Class B starts a fresh container on port Y — but
 * reuses the cached context still pointing at port X. Every query then fails with
 * {@code Connection to localhost:X refused}, surfacing as
 * {@code HikariPool-1 - Connection is not available ... (total=0)} across whole test classes at
 * once.
 *
 * <p>Nobody noticed because these tests had never run: {@code *IT} did not match surefire's
 * default includes until that was fixed in pom.xml. The first run of the suite failed en masse on
 * exactly this.
 *
 * <p>Starting the container in a static initializer and never closing it makes the behaviour match
 * what the comment always claimed. The container outlives every test class in the JVM, and
 * Testcontainers' Ryuk sidecar removes it when the JVM exits, so nothing leaks.
 *
 * <p><b>{@code @Isolated}.</b> {@code junit-platform.properties} turns on class-level parallel
 * execution for the suite, safe for the ~217 pure-unit {@code *Test.java} classes (fresh mocks
 * per class, no shared state). Every subclass of this one shares the single cached
 * {@link org.springframework.context.ApplicationContext} above, singleton beans included — and at
 * least one of those beans is provably not safe to exercise from concurrent, unrelated test
 * classes: a shared {@code RateLimiter} budget being drawn down by unrelated *IT classes in the
 * same run was diagnosed and fixed the same day this annotation was added. {@code @Isolated} is
 * inherited by every subclass and tells JUnit "run nothing else in parallel while a test in this
 * class hierarchy runs" — so the whole *IT population stays exactly as serial relative to each
 * other as it always has been, interleaved with (not blocked from) the unit tests running
 * concurrently around it, without requiring every other stateful singleton bean in the app to be
 * individually audited for concurrent-test-time safety first.
 */
@Isolated
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    // @SuppressWarnings("resource"): never closed by design, not an oversight -- closing it is the
    // exact bug described above. Ryuk reaps it on JVM exit.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finora_test")
            .withUsername("finora")
            .withPassword("finora");

    static {
        // Started here, not by the JUnit extension, so that no per-class lifecycle can stop it.
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPgProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Flyway runs against the real containerized Postgres exactly as it would in production —
        // this is what makes the soft-delete / JSONB / array-column tests meaningful.
    }
}
