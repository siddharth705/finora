package com.finora.transactions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Same harness as the other migration ITs: migrate to V137, seed at that shape, run V139 forward.
 *
 * <p>V139 adds two columns to a table that already holds real user data, one of them NOT NULL. What
 * is worth proving against real Postgres rather than by inspection is that an existing row survives
 * the NOT NULL addition (it takes the default rather than failing the migration), and that the
 * partial index is actually created -- the value-weighted review query this column exists to serve
 * groups on exactly that pair, and an index silently absent would only show up as a slow query much
 * later.
 */
class V139TransactionCounterpartyMigrationIT {

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
        migrateTo("137");
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

    @Test
    void anExistingTransactionSurvivesTheNotNullColumn_takingUnknownRatherThanFailing() throws SQLException {
        UUID user = seedUser();
        UUID txn = seedTransaction(user, seedAccount(user));

        assertThatCode(() -> migrateTo("139")).doesNotThrowAnyException();

        // 'UNKNOWN' is a real answer in this vocabulary, not a placeholder -- the classifier returns
        // it for roughly a fifth of real rows -- so an un-backfilled row is honestly labelled rather
        // than lying about having been typed.
        assertThat(string("SELECT counterparty_type FROM transactions WHERE id = ?", txn)).isEqualTo("UNKNOWN");
        assertThat(string("SELECT counterparty_key FROM transactions WHERE id = ?", txn)).isNull();
    }

    @Test
    void theReviewIndexExists_andIsPartialOnANonNullKey() throws SQLException {
        migrateTo("139");

        String def = string("SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                "idx_transactions_user_counterparty");
        assertThat(def).isNotNull();
        assertThat(def).contains("user_id").contains("counterparty_key");
        // Partial on purpose: a NULL key means "no identity derivable", which is never a group
        // anyone reviews, so indexing those rows would be pure cost.
        assertThat(def).contains("WHERE");
    }

    @Test
    void theTypeColumnAcceptsAnyValue_soAnUnknownEnumDegradesRatherThanBlockingABoot() throws SQLException {
        UUID user = seedUser();
        UUID txn = seedTransaction(user, seedAccount(user));
        migrateTo("139");

        // Deliberately no CHECK constraint and no DB enum, following decision_source (V17). A value
        // written by a newer deploy must not stop an older one from starting.
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE transactions SET counterparty_type = 'SOMETHING_NEW' WHERE id = ?")) {
            ps.setObject(1, txn);
            assertThatCode(ps::executeUpdate).doesNotThrowAnyException();
        }
        assertThat(string("SELECT counterparty_type FROM transactions WHERE id = ?", txn))
                .isEqualTo("SOMETHING_NEW");
    }

    // --- helpers -----------------------------------------------------------------------------

    private UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.setString(3, "hash");
            ps.setString(4, "Test User");
            ps.executeUpdate();
        }
        return id;
    }

    private UUID seedAccount(UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO accounts (id, user_id, name, account_type, balance) VALUES (?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, "Test Account");
            ps.setString(4, "SAVINGS");
            ps.setObject(5, BigDecimal.ZERO);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID seedTransaction(UUID userId, UUID accountId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO transactions (id, user_id, account_id, txn_date, amount, txn_type)
                VALUES (?, ?, ?, ?, ?, 'EXPENSE')
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setObject(3, accountId);
            ps.setObject(4, java.sql.Date.valueOf(LocalDate.now()));
            ps.setObject(5, BigDecimal.TEN);
            ps.executeUpdate();
        }
        return id;
    }

    private String string(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
