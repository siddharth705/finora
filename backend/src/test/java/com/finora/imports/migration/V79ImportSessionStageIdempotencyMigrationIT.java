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
 * Same shape as {@code V74ImportJobIdempotencyMigrationIT} (import_jobs' own idempotency
 * migration), applied to V79's equivalent for import_sessions. Builds the schema V79 assumes it
 * will meet -- migrated through V78, rows seeded by hand -- then runs V79 forward and checks what
 * actually happens, rather than only ever exercising the empty-schema case every other test run
 * uses.
 *
 * <p>Deliberately its own {@link PostgreSQLContainer}/{@link Flyway} instance rather than
 * {@code AbstractIntegrationTest}, for the same reason V74's own migration test is: Spring Boot's
 * Flyway autoconfiguration migrates straight to the latest version with no hook to pause at V78.
 */
class V79ImportSessionStageIdempotencyMigrationIT {

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
        try (Connection admin = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        migrateTo("78");
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

    private void migrateToLatestThroughV79() {
        migrateTo("79");
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

    /** Inserts one import_sessions row at the schema shape V78 leaves behind. file_content is
     *  required here (not by the JPA entity, but by import_sessions_file_content_or_object_key,
     *  the V76 CHECK constraint requiring one of file_content/object_key -- a real row always has
     *  one; this is a fixture standing in for the "no storage provider configured" shape). */
    private UUID seedSession(UUID userId, String contentHash, String status, Instant createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO import_sessions
                    (id, user_id, file_name, file_content, content_hash, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, "statement.csv");
            ps.setBytes(4, new byte[]{1, 2, 3});
            ps.setString(5, contentHash);
            ps.setString(6, status);
            ps.setTimestamp(7, Timestamp.from(createdAt));
            ps.setTimestamp(8, Timestamp.from(createdAt.plusSeconds(48 * 3600)));
            ps.executeUpdate();
        }
        return id;
    }

    private boolean rowExists(UUID sessionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM import_sessions WHERE id = ?")) {
            ps.setObject(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long countLiveContentIndexEntries() throws SQLException {
        try (java.sql.Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM pg_indexes WHERE indexname = 'idx_import_sessions_live_content'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void twoLiveStagedSessionsSameUserSameContentHash_olderIsDeletedNewerSurvives() throws SQLException {
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID older = seedSession(user, "hash-a", "STAGED", now.minusSeconds(60));
        UUID newer = seedSession(user, "hash-a", "STAGED", now);

        assertThatCode(this::migrateToLatestThroughV79).doesNotThrowAnyException();

        assertThat(rowExists(older)).as("superseded duplicate is deleted outright, not transitioned").isFalse();
        assertThat(rowExists(newer)).isTrue();
        assertThat(countLiveContentIndexEntries()).isEqualTo(1);
    }

    @Test
    void stagedAndConfirmedSharingAContentHash_confirmedRowUntouchedNoCollision() throws SQLException {
        // Re-importing a statement whose earlier session already CONFIRMED must stay legal --
        // the constraint only governs STAGED, so a CONFIRMED row sharing a hash is not a
        // collision the cleanup should touch.
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID confirmed = seedSession(user, "hash-b", "CONFIRMED", now.minusSeconds(3600));
        UUID staged = seedSession(user, "hash-b", "STAGED", now);

        assertThatCode(this::migrateToLatestThroughV79).doesNotThrowAnyException();

        assertThat(rowExists(confirmed)).isTrue();
        assertThat(rowExists(staged)).isTrue();
    }

    @Test
    void nullContentHash_neverTouchedByCleanupOrIndex() throws SQLException {
        UUID user = seedUser();
        Instant now = Instant.now();
        UUID a = seedSession(user, null, "STAGED", now.minusSeconds(30));
        UUID b = seedSession(user, null, "STAGED", now);

        assertThatCode(this::migrateToLatestThroughV79).doesNotThrowAnyException();

        // Both survive: content_hash IS NULL rows carry no identity to deduplicate on, per the
        // migration's own comment, and the partial index's WHERE clause excludes them too.
        assertThat(rowExists(a)).isTrue();
        assertThat(rowExists(b)).isTrue();
    }

    @Test
    void afterMigration_indexEnforcesUniquenessGoingForward() throws SQLException {
        UUID user = seedUser();
        migrateToLatestThroughV79();

        // Two fresh STAGED sessions for the same user+content_hash, inserted directly (bypassing
        // any application-level check), should now be rejected by the database itself -- the
        // guarantee V79 exists to provide, not just a migration-time cleanup.
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO import_sessions
                    (id, user_id, file_name, file_content, content_hash, status, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, 'STAGED', ?, ?)
                """)) {
            Instant now = Instant.now();
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, "a.csv");
            ps.setBytes(4, new byte[]{1, 2, 3});
            ps.setString(5, "hash-c");
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now.plusSeconds(172800)));
            ps.executeUpdate();

            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, user);
            ps.setString(3, "b.csv");
            ps.setBytes(4, new byte[]{1, 2, 3});
            ps.setString(5, "hash-c");
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now.plusSeconds(172800)));

            org.junit.jupiter.api.Assertions.assertThrows(SQLException.class, ps::executeUpdate);
        }
    }

    @Test
    void noPreexistingDuplicates_migrationSucceedsCleanlyLikeTheEmptySchemaCase() throws SQLException {
        assertThatCode(this::migrateToLatestThroughV79).doesNotThrowAnyException();
        assertThat(countLiveContentIndexEntries()).isEqualTo(1);
    }
}
