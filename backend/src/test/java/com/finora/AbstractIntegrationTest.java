package com.finora;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * <p><b>Profile guard.</b> pom.xml's surefire/failsafe {@code systemPropertyVariables} set
 * {@code spring.profiles.active=test} on the forked JVM -- that is the only reason
 * {@code application-test.yml} (which disables six background schedulers under test, among other
 * things) ever loads instead of {@code application.yml}'s {@code dev} default. Nothing enforces
 * that this class is only ever reached through that fork: an IDE's own "Run Test" launches its own
 * JVM directly against the compiled classpath, entirely outside Maven's plugin execution, and does
 * not carry that system property unless the run configuration sets it explicitly. That is exactly
 * the bug fixed once already (see the {@code spring.profiles.active} note on the surefire plugin in
 * pom.xml, and {@code MerchantLearningQueueIT}'s history) for the one path that was actually
 * exercised at the time -- a live scheduler thread, caught mid-suite, only reachable because the
 * profile silently was not "test". An IDE run is the same failure shape from a different entry
 * point, so it gets the same guard: checked before the container starts, so a bypassed run fails in
 * milliseconds with a clear cause instead of after paying for a Postgres container, or worse,
 * passing while quietly exercising live schedulers against shared state.
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
 *
 * <p><b>{@link #emptyTheSharedWorkQueues()}.</b> The flip side of sharing one database: the work
 * queues in it are shared too, and they are the one kind of table where another class's leftovers
 * change what a test observes. See that method for the failure it prevents.
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
        // See this class's own "Profile guard" doc comment above. Checked first, before the
        // container starts, so a bypassed run fails in milliseconds rather than after paying for
        // Postgres -- and reads System.getProperty, not the Environment/Spring context (which
        // does not exist yet at static-init time): this is exactly the JVM system property
        // pom.xml's surefire/failsafe systemPropertyVariables set, so it is what actually proves
        // that fork -- not just a value -- was reached.
        String activeProfile = System.getProperty("spring.profiles.active");
        if (!"test".equals(activeProfile)) {
            throw new IllegalStateException(
                    "spring.profiles.active is "
                            + (activeProfile == null ? "unset" : "'" + activeProfile + "'")
                            + ", not \"test\". This integration test was not launched through Maven's"
                            + " surefire/failsafe plugin execution (pom.xml's systemPropertyVariables"
                            + " is what normally sets this) -- an IDE's own \"Run Test\" button starts"
                            + " its own JVM directly against the compiled classpath and skips it unless"
                            + " the run configuration says otherwise. Without \"test\" active,"
                            + " application-test.yml never loads and every background scheduler it"
                            + " disables (merchant-learning queue, import-session cleanup,"
                            + " statement-storage sweep, account-purge sweep, audit-log redaction,"
                            + " Gmail state-cleanup/discovery) runs live against this class's shared"
                            + " Testcontainers Postgres instead -- the exact defect fixed once already,"
                            + " see the spring.profiles.active note on the surefire plugin in pom.xml."
                            + " Fix: run via `./mvnw test` / `./mvnw verify`, or add"
                            + " -Dspring.profiles.active=test to your IDE run configuration's VM"
                            + " options.");
        }
        // Started here, not by the JUnit extension, so that no per-class lifecycle can stop it.
        POSTGRES.start();
    }

    @Autowired private JdbcTemplate queueCleanupJdbc;

    /**
     * Empties the work queues before every integration test — BH-058, fixed at the source rather
     * than in each test that trips over it.
     *
     * <p>Both queues are claimed by a table-wide, {@code LIMIT}ed query ordered oldest-first:
     * {@code claimDueEvents} takes {@code MerchantLearningEventWorker.BATCH_SIZE} (50) and
     * {@code claimDueJobs} takes {@code ImportJobStore.BATCH_SIZE} (10). Neither is scoped by user
     * — a worker claims work, not one user's work, which is correct in production and hostile
     * here. Both queues are also disabled under test ({@code app.learning.queue.enabled} and
     * {@code app.import.queue.enabled} both default off), so rows a test enqueues stay PENDING or
     * QUEUED for the rest of the run unless that test drains them. Most do not.
     *
     * <p>So a test that enqueues its own row and then drains once is really asserting "fewer than
     * BATCH_SIZE older rows exist" — an assumption about the whole suite that it cannot see and
     * does not state. Past that threshold the drain claims a batch of other tests' leftovers and
     * the row under test is never claimed at all. It does not run late; it never runs. That
     * presents as an ordering-dependent flake and invites raising a timeout, which cannot help.
     *
     * <p>Measured before this existed: {@code ImportJobEndpointIT} climbed from one leftover job to
     * eight across its own methods, and {@code QueueOverheadMeasurementIT} began a method with
     * eleven — past its batch size of ten already, surviving only because its own warm-up happened
     * to drain ten first. {@code MerchantLearningNudgeIT} did fail, for exactly this reason.
     *
     * <p>Deleting rather than draining: draining runs real work (an import parse is not cheap) and
     * would make every test pay for whatever the previous one abandoned. Safe to delete outright —
     * these are queue tables, every dependent FK on {@code import_jobs} is {@code ON DELETE
     * CASCADE} (V72), and nothing references {@code merchant_learning_events} at all.
     *
     * <p>A superclass {@code @BeforeEach} runs before the subclass's, so a test class that enqueues
     * in its own setup is unaffected. {@code @Isolated} above means no other class is running to
     * refill the queues underneath this.
     */
    @BeforeEach
    void emptyTheSharedWorkQueues() {
        queueCleanupJdbc.update("DELETE FROM import_jobs");
        queueCleanupJdbc.update("DELETE FROM merchant_learning_events");
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
