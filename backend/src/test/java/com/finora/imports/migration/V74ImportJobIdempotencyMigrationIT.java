package com.finora.imports.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * V73/V74 have only ever run Flyway migration in one shape: from an empty Testcontainers schema, no
 * pre-existing rows in {@code import_jobs}. V74 in particular does a data mutation (cancelling
 * duplicate live jobs sharing a {@code content_hash}) immediately before creating a UNIQUE index, and
 * its own comment admits a pre-existing duplicate would fail {@code CREATE UNIQUE INDEX} at startup
 * -- "in practice this finds nothing... but 'in practice' is not a thing to bet a deploy on."
 *
 * <p>This test builds exactly the schema V74 assumes it will meet: a database migrated through V73
 * (so {@code import_jobs} has {@code version}/{@code recovery_count} but not yet the unique index or
 * V75's {@code source_format}), with rows seeded by hand to simulate a lived-in database rather than
 * an empty one. It then runs V74 forward and checks what actually happens.
 *
 * <p>Deliberately its own {@link PostgreSQLContainer} and its own {@link Flyway} instance rather than
 * {@code AbstractIntegrationTest} -- Spring Boot's Flyway autoconfiguration migrates straight to the
 * latest version on context startup with no hook to pause after V73, seed data, and resume. Driving
 * Flyway directly is the only way to control the target version.
 */
class V74ImportJobIdempotencyMigrationIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("finora_migration_test")
            .withUsername("finora")
            .withPassword("finora");

    @BeforeAll
    static void startContainer() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopContainer() {
        POSTGRES.stop();
    }

    private Connection connection;

    @BeforeEach
    void freshSchema() throws SQLException {
        // Each test gets a clean slate: drop and recreate the public schema, then migrate to V73
        // by hand. Cheaper than a new container per test and keeps every test's pre-V74 seed data
        // isolated from the others.
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        migrateTo("73");
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrateTo(String target) {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    private void migrateToLatestThroughV74() {
        // V75/V76 touch import_jobs too (source_format); scoping to V74 keeps this test about
        // exactly the migration under test and immune to unrelated later schema changes.
        migrateTo("74");
    }

    private UUID seedUser() throws SQLException {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, userId + "@example.test");
            ps.setString(3, "hash");
            ps.setString(4, "Test User");
            ps.executeUpdate();
        }
        return userId;
    }

    /** Inserts one import_jobs row at the schema shape V73 leaves behind (pre-V74: no unique index,
     *  pre-V75: no source_format column). */
    private UUID seedJob(UUID userId, String contentHash, String status, Instant createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO import_jobs (id, user_id, content_hash, file_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, contentHash);
            ps.setString(4, "statement.pdf");
            ps.setString(5, status);
            ps.setTimestamp(6, Timestamp.from(createdAt));
            ps.executeUpdate();
        }
        return id;
    }

    private String statusOf(UUID jobId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status FROM import_jobs WHERE id = ?")) {
            ps.setObject(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private long countLiveContentIndexEntries() throws SQLException {
        try (java.sql.Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_import_jobs_live_content'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void twoLiveJobsSameUserSameContentHash_olderIsCancelledNewerSurvives() throws SQLException {
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID older = seedJob(user, "hash-a", "QUEUED", now.minusSeconds(60));
        UUID newer = seedJob(user, "hash-a", "QUEUED", now);

        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();

        assertThat(statusOf(older)).isEqualTo("CANCELLED");
        assertThat(statusOf(newer)).isEqualTo("QUEUED");
        assertThat(countLiveContentIndexEntries()).isEqualTo(1);
    }

    @Test
    void threeWayCollision_onlyNewestSurvivesLive() throws SQLException {
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID oldest = seedJob(user, "hash-b", "QUEUED", now.minusSeconds(120));
        UUID middle = seedJob(user, "hash-b", "PARSING", now.minusSeconds(60));
        UUID newest = seedJob(user, "hash-b", "ANALYZING", now);

        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();

        assertThat(statusOf(oldest)).isEqualTo("CANCELLED");
        assertThat(statusOf(middle)).isEqualTo("CANCELLED");
        assertThat(statusOf(newest)).isEqualTo("ANALYZING");
    }

    @Test
    void collisionAcrossDifferentLiveStatuses_cleanupTreatsAllInFlightStatusesAsLive() throws SQLException {
        // QUEUED, PARSING, DEDUPING and IMPORTING are all "live" (not terminal). The cleanup CTE's
        // WHERE clause is `status NOT IN ('COMPLETED','FAILED','CANCELLED')` -- a negative list, not
        // an enumeration of specific in-flight statuses -- so it should catch a collision regardless
        // of which live status each duplicate happens to be sitting in, not just QUEUED vs QUEUED.
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID queued = seedJob(user, "hash-c", "QUEUED", now.minusSeconds(90));
        UUID processing = seedJob(user, "hash-c", "IMPORTING", now.minusSeconds(30));

        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();

        assertThat(statusOf(queued)).isEqualTo("CANCELLED");
        assertThat(statusOf(processing)).isEqualTo("IMPORTING");
    }

    @Test
    void oneLiveOneTerminal_terminalRowUntouchedNoCollision() throws SQLException {
        // A COMPLETED row sharing a content_hash with a live row is not a collision the cleanup (or
        // the resulting index) should touch -- re-importing after an earlier import finished is
        // explicitly meant to stay legal.
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID completed = seedJob(user, "hash-d", "COMPLETED", now.minusSeconds(3600));
        UUID live = seedJob(user, "hash-d", "QUEUED", now);

        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();

        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
        assertThat(statusOf(live)).isEqualTo("QUEUED");
    }

    @Test
    void nullContentHash_neverTouchedByCleanupOrIndex() throws SQLException {
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID a = seedJob(user, null, "QUEUED", now.minusSeconds(30));
        UUID b = seedJob(user, null, "QUEUED", now);

        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();

        // Both stay QUEUED: content_hash IS NULL rows carry no identity to deduplicate on, per the
        // migration's own comment, and the partial index's WHERE clause excludes them too.
        assertThat(statusOf(a)).isEqualTo("QUEUED");
        assertThat(statusOf(b)).isEqualTo("QUEUED");

        // Confirm two concurrent NULL-content_hash rows for the same user don't violate the unique
        // index that was just created -- proving the WHERE clause actually excludes them at the
        // index level, not just that the cleanup step left them alone.
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT count(*) FROM import_jobs WHERE user_id = ? AND content_hash IS NULL")) {
            ps.setObject(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void afterMigration_indexEnforcesUniquenessGoingForward() throws SQLException {
        UUID user = seedUser();
        migrateToLatestThroughV74();

        // Two fresh live jobs for the same user+content_hash, inserted directly (bypassing any
        // application-level check), should now be rejected by the database itself -- this is the
        // guarantee V74 exists to provide, not just a migration-time cleanup.
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO import_jobs (id, user_id, content_hash, file_name, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, "hash-e");
            ps.setString(4, "a.pdf");
            ps.setString(5, "QUEUED");
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, "hash-e");
            ps.setString(4, "b.pdf");
            ps.setString(5, "QUEUED");
            ps.setTimestamp(6, Timestamp.from(Instant.now()));

            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void noPreexistingDuplicates_migrationSucceedsCleanlyLikeTheEmptySchemaCase() throws SQLException {
        // Baseline: the shape every prior test run of V73/V74 has actually exercised (empty schema,
        // no seeded rows). Included here so a regression that breaks the ordinary case shows up
        // alongside the adversarial ones instead of only in a separate suite.
        assertThatCode(this::migrateToLatestThroughV74).doesNotThrowAnyException();
        assertThat(countLiveContentIndexEntries()).isEqualTo(1);
    }
}
