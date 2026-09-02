package com.finora.service;

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
 * Same shape as {@code V118CategoryDedupMigrationIT}: migrates through V122, seeds rows at that
 * schema shape, then runs V123 forward.
 *
 * <p>V123 does two things, and both are worth proving against real Postgres rather than trusting
 * by inspection:
 *
 * <ol>
 *   <li><b>It inserts a row into a table carrying a case-insensitive unique index.</b> V118
 *       replaced V1's case-sensitive {@code UNIQUE(user_id, name)} with
 *       {@code uq_categories_user_name_ci}. A user who had already hand-created "paid a person"
 *       would collide, and a failed migration does not degrade -- it stops the backend booting.
 *   <li><b>It rewrites {@code transactions.category_id} for existing user data.</b> The three
 *       conditions gating that update (decision source, the manual-set flag, and which category
 *       the row currently points at) are the whole safety argument for touching categorized
 *       transactions in a migration at all, so each one is pinned by a row that must NOT move.
 * </ol>
 *
 * <p>Its own {@link PostgreSQLContainer}/{@link Flyway} instance rather than
 * {@code AbstractIntegrationTest}, for the same reason V118's has one: Spring Boot's Flyway
 * autoconfiguration migrates straight to the latest version with no hook to pause before V123.
 */
class V123PaidAPersonCategoryMigrationIT {

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
        migrateTo("122");
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

    private void migrateThroughV123() {
        migrateTo("123");
    }

    // --- the seeding half ------------------------------------------------------------------

    @Test
    void seedsTheCategoryForEveryExistingUser_withTheTokensNewRegistrationsGet() throws SQLException {
        UUID first = seedUser();
        UUID second = seedUser();

        migrateThroughV123();

        for (UUID user : new UUID[]{first, second}) {
            assertThat(count("SELECT count(*) FROM categories WHERE user_id = ? AND name = 'Paid a Person'", user))
                    .isEqualTo(1);
            // is_system matters beyond tidiness: CategoryService.rename and delete both 403 on it,
            // which is what stops a user removing the category the detector routes into.
            assertThat(string("""
                    SELECT is_system || '/' || icon || '/' || color FROM categories
                    WHERE user_id = ? AND name = 'Paid a Person'
                    """, user))
                    .isEqualTo("true/users/orange");
        }
    }

    @Test
    void aUserWhoAlreadyHasTheNameInAnotherCasing_keepsTheirOwnRowExactlyAsItWas() throws SQLException {
        UUID user = seedUser();
        // A hand-created category, in the casing a person would actually type, with icon/color
        // they chose themselves after V118 made that possible.
        seedCategory(UUID.randomUUID(), user, "paid a person", false, "briefcase", "purple");

        // The collision case: without lower(name) in V123's NOT EXISTS this throws on
        // uq_categories_user_name_ci and the backend cannot boot.
        assertThatCode(this::migrateThroughV123).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", user)).isEqualTo(1);
        // Untouched: their spelling, their tokens, still deletable by them.
        assertThat(string("""
                SELECT name || '/' || is_system || '/' || icon || '/' || color FROM categories
                WHERE user_id = ?
                """, user))
                .isEqualTo("paid a person/false/briefcase/purple");
    }

    // --- the repointing half ---------------------------------------------------------------

    @Test
    void movesOnlyTheTransactionsTheDetectorItselfFiledUnderTransfer() throws SQLException {
        UUID user = seedUser();
        UUID account = seedAccount(user);
        UUID transfer = UUID.randomUUID();
        UUID groceries = UUID.randomUUID();
        seedCategory(transfer, user, "Transfer", true, "repeat", "blue");
        seedCategory(groceries, user, "Groceries", true, "shopping-cart", "green");

        UUID moves = seedTransaction(user, account, transfer, "STRUCTURAL_P2P", false);
        UUID manuallySet = seedTransaction(user, account, transfer, "STRUCTURAL_P2P", true);
        UUID otherSource = seedTransaction(user, account, transfer, "KEYWORD_MATCH", false);
        UUID elsewhere = seedTransaction(user, account, groceries, "STRUCTURAL_P2P", false);

        migrateThroughV123();

        UUID paidAPerson = categoryId(user, "Paid a Person");
        assertThat(categoryOf(moves)).isEqualTo(paidAPerson);
        // Each of these pins one of V123's three guard conditions. A row a person categorized, a
        // row some other decision path produced, and a row that was never sitting where the old
        // constant put it are all none of this migration's business.
        assertThat(categoryOf(manuallySet)).isEqualTo(transfer);
        assertThat(categoryOf(otherSource)).isEqualTo(transfer);
        assertThat(categoryOf(elsewhere)).isEqualTo(groceries);
    }

    @Test
    void movesEachUsersRowsIntoTheirOwnCategory_neverAnotherUsers() throws SQLException {
        UUID first = seedUser();
        UUID second = seedUser();
        UUID firstTransfer = UUID.randomUUID();
        UUID secondTransfer = UUID.randomUUID();
        seedCategory(firstTransfer, first, "Transfer", true, "repeat", "blue");
        seedCategory(secondTransfer, second, "Transfer", true, "repeat", "blue");
        UUID firstTxn = seedTransaction(first, seedAccount(first), firstTransfer, "STRUCTURAL_P2P", false);
        UUID secondTxn = seedTransaction(second, seedAccount(second), secondTransfer, "STRUCTURAL_P2P", false);

        migrateThroughV123();

        // What this pins is the OUTCOME -- two users, two separate destination categories, each
        // user's rows in their own -- not the user predicate in V123's WHERE clause. Deleting that
        // predicate was tried and this test still passed, because `t.category_id = origin.category
        // _id` already scopes to one user on its own: a category id belongs to exactly one user.
        // The predicate stays as explicit intent, but calling it load-bearing would be a claim
        // this test does not support.
        assertThat(categoryOf(firstTxn)).isEqualTo(categoryId(first, "Paid a Person"));
        assertThat(categoryOf(secondTxn)).isEqualTo(categoryId(second, "Paid a Person"));
        assertThat(categoryId(first, "Paid a Person")).isNotEqualTo(categoryId(second, "Paid a Person"));
    }

    @Test
    void aUserWithNoTransferCategoryStillGetsTheNewOne_andNothingIsRewritten() throws SQLException {
        UUID user = seedUser();
        UUID account = seedAccount(user);
        UUID groceries = UUID.randomUUID();
        seedCategory(groceries, user, "Groceries", true, "shopping-cart", "green");
        UUID txn = seedTransaction(user, account, groceries, "STRUCTURAL_P2P", false);

        // The two halves of V123 are independent: the seed must not depend on the repoint finding
        // anything to do, and vice versa.
        assertThatCode(this::migrateThroughV123).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ? AND name = 'Paid a Person'", user))
                .isEqualTo(1);
        assertThat(categoryOf(txn)).isEqualTo(groceries);
    }

    // --- seeding -----------------------------------------------------------------------------

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
            ps.setObject(5, BigDecimal.ZERO);
            ps.executeUpdate();
        }
        return accountId;
    }

    private void seedCategory(UUID id, UUID userId, String name, boolean isSystem, String icon, String color)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO categories (id, user_id, name, is_system, icon, color) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, name);
            ps.setBoolean(4, isSystem);
            ps.setString(5, icon);
            ps.setString(6, color);
            ps.executeUpdate();
        }
    }

    private UUID seedTransaction(UUID userId, UUID accountId, UUID categoryId,
                                 String decisionSource, boolean categoryManuallySet) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO transactions
                    (id, user_id, account_id, category_id, txn_date, amount, txn_type,
                     decision_source, category_manually_set)
                VALUES (?, ?, ?, ?, ?, ?, 'EXPENSE', ?, ?)
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setObject(3, accountId);
            ps.setObject(4, categoryId);
            ps.setObject(5, java.sql.Date.valueOf(LocalDate.now()));
            ps.setObject(6, BigDecimal.TEN);
            ps.setString(7, decisionSource);
            ps.setBoolean(8, categoryManuallySet);
            ps.executeUpdate();
        }
        return id;
    }

    // --- reading -----------------------------------------------------------------------------

    private UUID categoryId(UUID userId, String name) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id FROM categories WHERE user_id = ? AND name = ?")) {
            ps.setObject(1, userId);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private UUID categoryOf(UUID transactionId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT category_id FROM transactions WHERE id = ?")) {
            ps.setObject(1, transactionId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getObject(1, UUID.class) : null;
            }
        }
    }

    private long count(String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
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
