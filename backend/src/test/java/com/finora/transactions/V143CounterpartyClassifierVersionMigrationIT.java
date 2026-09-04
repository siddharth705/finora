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
 * Same harness as {@link V142TransactionCounterpartyMigrationIT}: migrate to the previous version,
 * seed at that shape, run V143 forward.
 *
 * <p>The thing worth proving against real Postgres is the one V143 exists for -- that an existing
 * row lands in the NULL state rather than picking up a default. A DEFAULT slipped into that column
 * would be silent, would look harmless, and would destroy the entire distinction: every historical
 * row would claim to have been classified, the backfill's discovery query would match nothing, and
 * the backfill would appear to succeed by doing nothing at all.
 */
class V143CounterpartyClassifierVersionMigrationIT {

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
        migrateTo("142");
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
    void anExistingRowLandsInTheNullState_whichIsTheWholePointOfTheColumn() throws SQLException {
        UUID user = seedUser();
        UUID txn = seedTransaction(user, seedAccount(user));

        assertThatCode(() -> migrateTo("143")).doesNotThrowAnyException();

        // NULL means "no classifier has ever looked at this row". V142 already gave the row
        // counterparty_type = 'UNKNOWN', which is a REAL answer in this vocabulary -- so without a
        // NULL here the row is indistinguishable from one the classifier examined and gave up on.
        assertThat(object("SELECT counterparty_classifier_version FROM transactions WHERE id = ?", txn))
                .isNull();
        assertThat(string("SELECT counterparty_type FROM transactions WHERE id = ?", txn))
                .isEqualTo("UNKNOWN");
    }

    @Test
    void theColumnHasNoDefault_soANewRowIsUntypedUntilSomethingTypesIt() throws SQLException {
        migrateTo("143");

        // Asserted at the catalog rather than by inserting a row, because this is exactly the
        // property a later "tidy up the schema" edit would add a default to without noticing.
        assertThat(string("""
                SELECT column_default FROM information_schema.columns
                WHERE table_name = 'transactions' AND column_name = ?
                """, "counterparty_classifier_version")).isNull();
        assertThat(string("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_name = 'transactions' AND column_name = ?
                """, "counterparty_classifier_version")).isEqualTo("YES");
    }

    @Test
    void theDiscoveryIndexExists() throws SQLException {
        migrateTo("143");

        String def = string("SELECT indexdef FROM pg_indexes WHERE indexname = ?",
                "idx_transactions_counterparty_classifier_version");
        assertThat(def).isNotNull();
        assertThat(def).contains("counterparty_classifier_version");
        // NOT partial, deliberately -- see V143's own comment. A partial index on IS NULL would go
        // blind exactly when CounterpartyClassifier.VERSION is bumped and the backfill has the most
        // work to do.
        assertThat(def).doesNotContain("WHERE");
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
        Object value = object(sql, args);
        return value == null ? null : value.toString();
    }

    private Object object(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1) : null;
            }
        }
    }
}
