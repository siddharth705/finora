package com.finora.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SEC-03 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Same shape as Sprint
 * 1's V97 migration IT: migrates through the version just before this one, then checks what the
 * database itself now enforces -- the two real constraints V98 exists to add (one credential row
 * per user; one live challenge per raw token), not just that the migration runs without error.
 */
class V98AdminTotpMfaMigrationIT {

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
        // Targets 96, not 97: this worktree branched before Sprint 1's V97
        // (transaction_idempotency_key, a sibling PR) landed on this branch's own migration
        // folder, so V96 is the real "version immediately before this one" here.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("96")
                .load()
                .migrate();
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrateToLatestThroughV98() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("98")
                .load()
                .migrate();
    }

    private UUID seedUser() throws SQLException {
        UUID userId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, userId);
            ps.setString(2, userId + "@example.test");
            ps.setString(3, "hash");
            ps.setString(4, "Test Admin");
            ps.executeUpdate();
        }
        return userId;
    }

    private void insertCredential(UUID userId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO admin_totp_credentials (id, user_id, encrypted_secret, encryption_key_id)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, "ciphertext");
            ps.setString(4, "key-1");
            ps.executeUpdate();
        }
    }

    private void insertChallenge(UUID userId, String tokenHash) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO admin_mfa_challenges (id, user_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, tokenHash);
            ps.setObject(4, java.sql.Timestamp.from(Instant.now().plusSeconds(300)));
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void migrationRunsCleanlyOnAnEmptySchema() {
        assertThatCode(this::migrateToLatestThroughV98).doesNotThrowAnyException();
    }

    @Test
    void afterMigration_aSecondCredentialRowForTheSameUser_isRejected() throws SQLException {
        migrateToLatestThroughV98();
        UUID user = seedUser();

        insertCredential(user);
        assertThrows(SQLException.class, () -> insertCredential(user));
    }

    @Test
    void afterMigration_credentialRowsForDifferentUsers_bothSucceed() throws SQLException {
        migrateToLatestThroughV98();
        UUID userA = seedUser();
        UUID userB = seedUser();

        assertThatCode(() -> {
            insertCredential(userA);
            insertCredential(userB);
        }).doesNotThrowAnyException();
    }

    @Test
    void afterMigration_aDuplicateChallengeTokenHash_isRejected() throws SQLException {
        migrateToLatestThroughV98();
        UUID user = seedUser();

        insertChallenge(user, "same-hash");
        assertThrows(SQLException.class, () -> insertChallenge(user, "same-hash"));
    }

    @Test
    void afterMigration_deletingAUser_cascadesToTheirMfaRows() throws SQLException {
        migrateToLatestThroughV98();
        UUID user = seedUser();
        insertCredential(user);
        insertChallenge(user, "some-hash");

        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }

        try (PreparedStatement ps1 = connection.prepareStatement(
                "SELECT count(*) FROM admin_totp_credentials WHERE user_id = ?")) {
            ps1.setObject(1, user);
            var rs1 = ps1.executeQuery();
            rs1.next();
            org.assertj.core.api.Assertions.assertThat(rs1.getLong(1)).isZero();
        }
        try (PreparedStatement ps2 = connection.prepareStatement(
                "SELECT count(*) FROM admin_mfa_challenges WHERE user_id = ?")) {
            ps2.setObject(1, user);
            var rs2 = ps2.executeQuery();
            rs2.next();
            org.assertj.core.api.Assertions.assertThat(rs2.getLong(1)).isZero();
        }
    }
}
