package com.finora.transactions;

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
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Same shape as
 * {@code V74ImportJobIdempotencyMigrationIT}/{@code V79ImportSessionStageIdempotencyMigrationIT}:
 * migrates through the version just before V97, seeds rows at that schema shape, runs V97 forward,
 * and checks what the database itself now enforces -- unlike those two, V97 adds a brand-new
 * nullable column rather than reconciling pre-existing duplicate data, so there is no
 * migration-time cleanup step to test, only the constraint it leaves behind.
 *
 * <p>Its own {@link PostgreSQLContainer}/{@link Flyway} instance rather than
 * {@code AbstractIntegrationTest}, for the same reason V74/V79's own migration tests are: Spring
 * Boot's Flyway autoconfiguration migrates straight to the latest version with no hook to pause.
 */
class V97TransactionIdempotencyKeyMigrationIT {

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
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("96")
                .load()
                .migrate();
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private void migrateToLatestThroughV97() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target("97")
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
            ps.setString(4, "Test User");
            ps.executeUpdate();
        }
        return userId;
    }

    private UUID seedAccount(UUID userId) throws SQLException {
        UUID accountId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (id, user_id, name, account_type, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, accountId);
            ps.setObject(2, userId);
            ps.setString(3, "Test Account");
            ps.setString(4, "SAVINGS");
            ps.setObject(5, java.math.BigDecimal.ZERO);
            ps.executeUpdate();
        }
        return accountId;
    }

    private void insertTransaction(UUID userId, UUID accountId, String idempotencyKey) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, amount, txn_type, idempotency_key)
                VALUES (?, ?, ?, ?, ?, 'EXPENSE', ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, accountId);
            ps.setObject(4, java.sql.Date.valueOf(LocalDate.now()));
            ps.setObject(5, java.math.BigDecimal.TEN);
            ps.setString(6, idempotencyKey);
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------------------------------

    @Test
    void migrationRunsCleanlyOnAnEmptyTable() {
        assertThatCode(this::migrateToLatestThroughV97).doesNotThrowAnyException();
    }

    @Test
    void afterMigration_sameUserSameKeyTwice_secondInsertRejected() throws SQLException {
        migrateToLatestThroughV97();
        UUID user = seedUser();
        UUID account = seedAccount(user);

        insertTransaction(user, account, "client-key-1");
        assertThrows(SQLException.class, () -> insertTransaction(user, account, "client-key-1"));
    }

    @Test
    void afterMigration_sameKeyDifferentUsers_bothInsertsSucceed() throws SQLException {
        migrateToLatestThroughV97();
        UUID userA = seedUser();
        UUID accountA = seedAccount(userA);
        UUID userB = seedUser();
        UUID accountB = seedAccount(userB);

        assertThatCode(() -> {
            insertTransaction(userA, accountA, "shared-key");
            insertTransaction(userB, accountB, "shared-key");
        }).doesNotThrowAnyException();
    }

    @Test
    void afterMigration_nullKeyRepeatedManyTimes_neverCollides() throws SQLException {
        migrateToLatestThroughV97();
        UUID user = seedUser();
        UUID account = seedAccount(user);

        assertThatCode(() -> {
            insertTransaction(user, account, null);
            insertTransaction(user, account, null);
            insertTransaction(user, account, null);
        }).doesNotThrowAnyException();
    }
}
