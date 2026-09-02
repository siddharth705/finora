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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Same shape as {@code V79ImportSessionStageIdempotencyMigrationIT}: migrates through V115, seeds
 * rows at that schema shape, then runs V118 forward.
 *
 * <p>What is being proved is that V118's {@code CREATE UNIQUE INDEX ... (user_id, lower(name))}
 * can actually be created on real data. V1's {@code UNIQUE(user_id, name)} is case-sensitive, so
 * "Fuel" and "fuel" for one user are two legal rows -- a state
 * {@code CategoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc}'s own doc comment records
 * as known and expected. Without the de-duplication step V118 now carries, one such pair anywhere
 * aborts the whole migration and the backend cannot boot.
 *
 * <p>Its own {@link PostgreSQLContainer}/{@link Flyway} instance rather than
 * {@code AbstractIntegrationTest}, for the same reason the V74/V79/V97 migration tests have one:
 * Spring Boot's Flyway autoconfiguration migrates straight to the latest version with no hook to
 * pause before V118.
 */
class V118CategoryDedupMigrationIT {

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
        // V115 is the highest version below V118 that actually exists (V116/V117 were never used
        // -- V118 was renumbered up from V116 after an origin/main collision).
        migrateTo("115");
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

    private void migrateThroughV118() {
        migrateTo("118");
    }

    // --- seeding -------------------------------------------------------------------------------

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

    /** Inserts a category with an explicitly chosen id, so tests can decide which row is the
     *  lowest-id survivor rather than depending on random UUIDs. */
    private void seedCategory(UUID id, UUID userId, String name, boolean isSystem) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO categories (id, user_id, name, is_system) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setString(3, name);
            ps.setBoolean(4, isSystem);
            ps.executeUpdate();
        }
    }

    private UUID seedMerchant(UUID userId, String canonicalName) throws SQLException {
        UUID merchantId = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO merchants (id, user_id, canonical_name) VALUES (?, ?, ?)")) {
            ps.setObject(1, merchantId);
            ps.setObject(2, userId);
            ps.setString(3, canonicalName);
            ps.executeUpdate();
        }
        return merchantId;
    }

    private UUID seedTransaction(UUID userId, UUID accountId, UUID categoryId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO transactions (id, user_id, account_id, category_id, txn_date, amount, txn_type)
                VALUES (?, ?, ?, ?, ?, ?, 'EXPENSE')
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setObject(3, accountId);
            ps.setObject(4, categoryId);
            ps.setObject(5, java.sql.Date.valueOf(LocalDate.now()));
            ps.setObject(6, java.math.BigDecimal.TEN);
            ps.executeUpdate();
        }
        return id;
    }

    private UUID seedBudget(UUID userId, UUID categoryId, String limit) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO budgets (id, user_id, category_id, monthly_limit) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.setObject(3, categoryId);
            ps.setObject(4, new java.math.BigDecimal(limit));
            ps.executeUpdate();
        }
        return id;
    }

    private void seedLearning(UUID userId, UUID merchantId, UUID categoryId,
                              int confirmationCount, int confidence, Instant lastConfirmedAt) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO merchant_category_learning
                    (id, user_id, merchant_id, category_id, confirmation_count, confidence, last_confirmed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, merchantId);
            ps.setObject(4, categoryId);
            ps.setInt(5, confirmationCount);
            ps.setInt(6, confidence);
            ps.setTimestamp(7, Timestamp.from(lastConfirmedAt));
            ps.executeUpdate();
        }
    }

    private void seedAudit(UUID userId, UUID merchantId, UUID previousCategoryId, UUID newCategoryId)
            throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO merchant_learning_audit
                    (id, user_id, merchant_id, action, previous_category_id, new_category_id)
                VALUES (?, ?, ?, 'CORRECTED', ?, ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, merchantId);
            ps.setObject(4, previousCategoryId);
            ps.setObject(5, newCategoryId);
            ps.executeUpdate();
        }
    }

    private void seedLearningEvent(UUID userId, UUID merchantId, UUID categoryId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO merchant_learning_events (id, user_id, merchant_id, category_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setObject(3, merchantId);
            ps.setObject(4, categoryId);
            ps.executeUpdate();
        }
    }

    private void seedMerchantCategoryMap(UUID userId, String normalizedDesc, UUID categoryId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO merchant_category_map (id, user_id, normalized_desc, category_id) VALUES (?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, normalizedDesc);
            ps.setObject(4, categoryId);
            ps.executeUpdate();
        }
    }

    // --- reading -------------------------------------------------------------------------------

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

    /**
     * n ids sorted the way POSTGRES orders uuid, so {@code [0]} is the survivor V118's
     * {@code min(id)} keeps (the same row {@code resolveOrCreateCategory} already resolves to).
     *
     * <p>Deliberately NOT {@code UUID::compareTo}: Java compares the two halves as SIGNED longs,
     * so any uuid with the high bit set sorts negative there, while Postgres compares the 16
     * bytes unsigned. The canonical lowercase hex string's natural ordering is exactly Postgres'.
     */
    private static UUID[] pgOrderedIds(int n) {
        UUID[] ids = new UUID[n];
        for (int i = 0; i < n; i++) ids[i] = UUID.randomUUID();
        java.util.Arrays.sort(ids, java.util.Comparator.comparing(UUID::toString));
        return ids;
    }

    private static UUID[] orderedPair() {
        return pgOrderedIds(2);
    }

    // --- tests ---------------------------------------------------------------------------------

    @Test
    void migrationRunsCleanlyOnAnEmptySchema() {
        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();
    }

    @Test
    void caseVariantDuplicatesWithNoReferences_migrationSucceedsAndKeepsTheLowestIdRow() throws SQLException {
        UUID user = seedUser();
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);

        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(string("SELECT name FROM categories WHERE id = ?", pair[0])).isEqualTo("Fuel");
    }

    @Test
    void afterMigration_theCaseInsensitiveIndexIsEnforced() throws SQLException {
        UUID user = seedUser();
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);

        migrateThroughV118();

        assertThatCode(() -> seedCategory(UUID.randomUUID(), user, "FUEL", false))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void transactionsAndMerchantMapOnTheLoser_areRepointedAtTheSurvivor() throws SQLException {
        UUID user = seedUser();
        UUID account = seedAccount(user);
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        UUID onSurvivor = seedTransaction(user, account, pair[0]);
        UUID onLoser = seedTransaction(user, account, pair[1]);
        seedMerchantCategoryMap(user, "indian oil", pair[1]);

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM transactions WHERE category_id = ?", pair[0])).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM transactions WHERE id IN (?, ?) AND category_id IS NULL",
                onSurvivor, onLoser)).isZero();
        assertThat(count("SELECT count(*) FROM merchant_category_map WHERE category_id = ?", pair[0])).isEqualTo(1);
    }

    @Test
    void bothRowsHaveABudget_theSurvivorsIsKeptAndTheLosersIsDropped() throws SQLException {
        UUID user = seedUser();
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        seedBudget(user, pair[0], "5000.00");
        UUID loser = seedBudget(user, pair[1], "1200.00");

        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM budgets WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(string("SELECT monthly_limit::text FROM budgets WHERE user_id = ?", user))
                .isEqualTo("5000.00");

        // ...and the limit that was dropped is recorded, since nothing else in the system retains
        // it once the row is gone.
        assertThat(count("SELECT count(*) FROM v118_dropped_budgets")).isEqualTo(1);
        assertThat(string("SELECT user_id::text FROM v118_dropped_budgets")).isEqualTo(user.toString());
        assertThat(string("SELECT original_category_id::text FROM v118_dropped_budgets"))
                .isEqualTo(pair[1].toString());
        assertThat(string("SELECT surviving_category_id::text FROM v118_dropped_budgets"))
                .isEqualTo(pair[0].toString());
        assertThat(string("SELECT monthly_limit::text FROM v118_dropped_budgets")).isEqualTo("1200.00");
        assertThat(string("SELECT budget_id::text FROM v118_dropped_budgets")).isEqualTo(loser.toString());
    }

    @Test
    void onlyTheLoserHasABudget_itIsRepointedRatherThanDropped() throws SQLException {
        UUID user = seedUser();
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        seedBudget(user, pair[1], "1200.00");

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM budgets WHERE user_id = ? AND category_id = ?", user, pair[0]))
                .isEqualTo(1);
    }

    @Test
    void learningRowsForTheSameMerchant_areMergedNotDestroyed() throws SQLException {
        UUID user = seedUser();
        UUID merchant = seedMerchant(user, "Indian Oil");
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-06-01T00:00:00Z");
        seedLearning(user, merchant, pair[0], 3, 100, older);
        seedLearning(user, merchant, pair[1], 11, 100, newer);

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM merchant_category_learning WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT confirmation_count FROM merchant_category_learning WHERE category_id = ?", pair[0]))
                .isEqualTo(14);
        assertThat(count("SELECT confidence FROM merchant_category_learning WHERE category_id = ?", pair[0]))
                .isEqualTo(100);
        assertThat(string("SELECT last_confirmed_at::text FROM merchant_category_learning WHERE category_id = ?",
                pair[0])).startsWith("2026-06-01");
    }

    @Test
    void learningRowOnlyOnTheLoser_isRepointedAndConfidenceRecomputedAcrossTheMerchant() throws SQLException {
        UUID user = seedUser();
        UUID merchant = seedMerchant(user, "Indian Oil");
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        UUID other = UUID.randomUUID();
        seedCategory(other, user, "Transport", false);
        seedLearning(user, merchant, pair[1], 3, 50, Instant.parse("2026-01-01T00:00:00Z"));
        seedLearning(user, merchant, other, 1, 50, Instant.parse("2026-01-01T00:00:00Z"));

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM merchant_category_learning WHERE category_id = ?", pair[0]))
                .isEqualTo(1);
        // 3 of 4 confirmations, and the sibling recomputed alongside it -- ConfidenceEngine's
        // share-of-total formula, not the stale 50/50 the rows were seeded with.
        assertThat(count("SELECT confidence FROM merchant_category_learning WHERE category_id = ?", pair[0]))
                .isEqualTo(75);
        assertThat(count("SELECT confidence FROM merchant_category_learning WHERE category_id = ?", other))
                .isEqualTo(25);
    }

    @Test
    void auditAndQueuedEventReferences_areRepointedNotBlockedOrCascaded() throws SQLException {
        UUID user = seedUser();
        UUID merchant = seedMerchant(user, "Indian Oil");
        UUID[] pair = orderedPair();
        seedCategory(pair[0], user, "Fuel", false);
        seedCategory(pair[1], user, "fuel", false);
        // merchant_learning_audit's FKs are NO ACTION -- these rows would REFUSE the loser's
        // delete outright if they were not repointed first.
        seedAudit(user, merchant, pair[1], pair[1]);
        seedLearningEvent(user, merchant, pair[1]);

        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM merchant_learning_audit WHERE previous_category_id = ? "
                + "AND new_category_id = ?", pair[0], pair[0])).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_learning_events WHERE category_id = ?", pair[0]))
                .isEqualTo(1);
    }

    @Test
    void aSystemRowInTheGroup_promotesSystemnessAndItsNameOntoTheSurvivor() throws SQLException {
        UUID user = seedUser();
        UUID[] pair = orderedPair();
        // Worst case: the user-created lowercase row is the lowest id, so the survivor is the
        // NON-system row and would otherwise silently demote a seeded system category.
        seedCategory(pair[0], user, "dining", false);
        seedCategory(pair[1], user, "Dining", true);

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(string("SELECT name FROM categories WHERE id = ?", pair[0])).isEqualTo("Dining");
        assertThat(count("SELECT count(*) FROM categories WHERE id = ? AND is_system", pair[0])).isEqualTo(1);
        // And the icon/color backfill further down V118 (which matches on is_system AND name)
        // therefore still finds it.
        assertThat(string("SELECT icon FROM categories WHERE id = ?", pair[0])).isEqualTo("utensils");
    }

    @Test
    void threeWayDuplicateWithReferencesOnEveryRow_collapsesToOne() throws SQLException {
        UUID user = seedUser();
        UUID account = seedAccount(user);
        UUID merchant = seedMerchant(user, "Indian Oil");
        UUID[] ids = pgOrderedIds(3);
        seedCategory(ids[0], user, "Fuel", false);
        seedCategory(ids[1], user, "fuel", false);
        seedCategory(ids[2], user, "FUEL", false);
        for (UUID id : ids) {
            seedTransaction(user, account, id);
            seedLearning(user, merchant, id, 2, 33, Instant.parse("2026-01-01T00:00:00Z"));
        }
        // No budget on the survivor: two losers both carry one, which is the case a blind repoint
        // would break on budgets' UNIQUE(user_id, category_id).
        seedBudget(user, ids[1], "1200.00");
        seedBudget(user, ids[2], "1500.00");

        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE category_id = ?", ids[0])).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM budgets WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(string("SELECT monthly_limit::text FROM budgets WHERE user_id = ?", user)).isEqualTo("1200.00");
        assertThat(count("SELECT count(*) FROM merchant_category_learning WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT confirmation_count FROM merchant_category_learning WHERE category_id = ?", ids[0]))
                .isEqualTo(6);
    }

    /**
     * The 25 names {@code AuthService.DEFAULT_CATEGORIES} seeded at registration <b>as of
     * V118</b>, with the icon/color V118's backfill is expected to write onto each of them. Kept
     * in the same order as that map so a drift between the two is obvious when reading them side
     * by side.
     *
     * <p>That map has since grown a 26th entry ("Paid a Person", V123). It is deliberately absent
     * here and must stay absent: this fixture describes the schema V118 itself backfills, and this
     * test migrates only as far as V118, so a category introduced five migrations later does not
     * exist yet at the point these assertions run.
     */
    private static final String[][] DEFAULT_CATEGORIES = {
            {"Salary", "arrow-down-circle", "green"},
            {"Rent", "home", "blue"},
            {"Groceries", "shopping-cart", "green"},
            {"Dining", "utensils", "orange"},
            {"Transport", "car", "gray"},
            {"Utilities", "zap", "yellow"},
            {"Shopping", "shopping-bag", "purple"},
            {"Health", "heart-pulse", "red"},
            {"Entertainment", "film", "pink"},
            {"Investments", "trending-up", "teal"},
            {"Fees/Interest", "percent", "gray"},
            {"Transfer", "repeat", "blue"},
            {"Friend Repayment", "users", "teal"},
            {"Loan EMI", "landmark", "red"},
            {"Insurance", "shield", "blue"},
            {"Education", "graduation-cap", "purple"},
            {"Subscriptions", "refresh-cw", "pink"},
            {"Travel", "plane", "teal"},
            {"Gifts & Donations", "gift", "pink"},
            {"Pets", "paw-print", "orange"},
            {"Home & Furnishing", "sofa", "yellow"},
            {"Taxes", "receipt", "gray"},
            {"Cash Withdrawal", "banknote", "green"},
            {"Business Expenses", "briefcase", "blue"},
            {"Other", "tag", "gray"},
    };

    /**
     * The shape essentially every real environment is in on deploy day: a registered user with the
     * full seeded category set, real referencing rows, and NOT ONE case-variant duplicate anywhere.
     * The de-duplication block must be a complete no-op here -- every id, every FK and every row
     * count identical afterwards -- with the icon/color backfill the only thing that touches data.
     */
    @Test
    void aPopulatedDuplicateFreeSchema_isLeftEntirelyUntouchedApartFromTheIconColorBackfill()
            throws SQLException {
        UUID user = seedUser();
        UUID account = seedAccount(user);
        UUID merchant = seedMerchant(user, "Indian Oil");

        java.util.Map<String, UUID> categoryIds = new java.util.LinkedHashMap<>();
        for (String[] row : DEFAULT_CATEGORIES) {
            UUID id = UUID.randomUUID();
            seedCategory(id, user, row[0], true);
            categoryIds.put(row[0], id);
        }
        // Two user-created categories alongside the seeded ones -- distinct names, so still no
        // duplicate group, but they must not pick up a system icon/color either.
        UUID fuel = UUID.randomUUID();
        UUID gym = UUID.randomUUID();
        seedCategory(fuel, user, "Fuel", false);
        seedCategory(gym, user, "gym", false);

        UUID groceriesTxn = seedTransaction(user, account, categoryIds.get("Groceries"));
        UUID fuelTxn = seedTransaction(user, account, fuel);
        UUID budget = seedBudget(user, categoryIds.get("Dining"), "8000.00");
        Instant confirmed = Instant.parse("2026-03-01T00:00:00Z");
        seedLearning(user, merchant, fuel, 7, 64, confirmed);
        seedLearning(user, merchant, categoryIds.get("Transport"), 4, 36, confirmed);
        seedMerchantCategoryMap(user, "indian oil", fuel);
        seedAudit(user, merchant, categoryIds.get("Transport"), fuel);
        seedLearningEvent(user, merchant, fuel);

        assertThatCode(this::migrateThroughV118).doesNotThrowAnyException();

        // Nothing added, nothing removed, anywhere.
        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", user))
                .isEqualTo(DEFAULT_CATEGORIES.length + 2);
        assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", user)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM budgets WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_category_learning WHERE user_id = ?", user)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM merchant_category_map WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_learning_audit WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_learning_events WHERE user_id = ?", user)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM v118_dropped_budgets")).isZero();

        // Every seeded row kept its own id, its own exact name and its system flag, and got the
        // icon/color AuthService now hands out at registration.
        for (String[] row : DEFAULT_CATEGORIES) {
            UUID id = categoryIds.get(row[0]);
            assertThat(string("SELECT name FROM categories WHERE id = ?", id)).isEqualTo(row[0]);
            assertThat(string("SELECT icon FROM categories WHERE id = ?", id)).isEqualTo(row[1]);
            assertThat(string("SELECT color FROM categories WHERE id = ?", id)).isEqualTo(row[2]);
            assertThat(count("SELECT count(*) FROM categories WHERE id = ? AND is_system", id)).isEqualTo(1);
        }
        // User-created rows keep the column defaults rather than a system row's tokens.
        assertThat(string("SELECT name FROM categories WHERE id = ?", fuel)).isEqualTo("Fuel");
        assertThat(string("SELECT icon || '/' || color FROM categories WHERE id = ?", fuel)).isEqualTo("tag/gray");
        assertThat(string("SELECT name FROM categories WHERE id = ?", gym)).isEqualTo("gym");
        assertThat(string("SELECT icon || '/' || color FROM categories WHERE id = ?", gym)).isEqualTo("tag/gray");

        // Every referencing row still points where it did, with its own values intact.
        assertThat(string("SELECT category_id::text FROM transactions WHERE id = ?", groceriesTxn))
                .isEqualTo(categoryIds.get("Groceries").toString());
        assertThat(string("SELECT category_id::text FROM transactions WHERE id = ?", fuelTxn))
                .isEqualTo(fuel.toString());
        assertThat(string("SELECT category_id::text FROM budgets WHERE id = ?", budget))
                .isEqualTo(categoryIds.get("Dining").toString());
        assertThat(string("SELECT monthly_limit::text FROM budgets WHERE id = ?", budget)).isEqualTo("8000.00");
        assertThat(count("SELECT confirmation_count FROM merchant_category_learning WHERE category_id = ?", fuel))
                .isEqualTo(7);
        // Untouched, NOT recomputed: no survivor exists, so the confidence sweep must not reach
        // these rows even though 64/36 is not what the share-of-total formula would produce.
        assertThat(count("SELECT confidence FROM merchant_category_learning WHERE category_id = ?", fuel))
                .isEqualTo(64);
        assertThat(count("SELECT confidence FROM merchant_category_learning WHERE category_id = ?",
                categoryIds.get("Transport"))).isEqualTo(36);
        assertThat(string("SELECT last_confirmed_at::text FROM merchant_category_learning WHERE category_id = ?",
                fuel)).startsWith("2026-03-01");
        assertThat(count("SELECT count(*) FROM merchant_category_map WHERE category_id = ?", fuel)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_learning_audit WHERE previous_category_id = ? "
                + "AND new_category_id = ?", categoryIds.get("Transport"), fuel)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant_learning_events WHERE category_id = ?", fuel)).isEqualTo(1);
    }

    @Test
    void duplicatesAcrossDifferentUsers_areNotConflated() throws SQLException {
        UUID userA = seedUser();
        UUID userB = seedUser();
        seedCategory(UUID.randomUUID(), userA, "Fuel", false);
        seedCategory(UUID.randomUUID(), userB, "fuel", false);

        migrateThroughV118();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", userA)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ?", userB)).isEqualTo(1);
    }
}
