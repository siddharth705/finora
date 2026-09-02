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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Same harness as {@code V123PaidAPersonCategoryMigrationIT}: migrate to V123, seed at that shape,
 * then run V124 forward.
 *
 * <p>V124 is one UPDATE, but it writes a user-visible label and it writes it into a table carrying
 * a case-insensitive unique index, so both halves of its WHERE clause are worth proving: the
 * {@code is_system} guard (a category the user created is theirs) and the NOT EXISTS guard (a
 * collision here aborts the migration, and a failed migration stops the backend booting).
 */
class V124PersonalTransferRenameMigrationIT {

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
        migrateTo("123");
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
    void theSeededCategoryIsRenamed_andEveryTransactionOnItInheritsTheNewLabel() throws SQLException {
        UUID user = seedUser();
        // V123 seeded the row; this is what every existing user is in.
        UUID category = categoryId(user, "Paid a Person");
        assertThat(category).isNotNull();

        migrateTo("124");

        assertThat(categoryId(user, "Paid a Person")).isNull();
        UUID renamed = categoryId(user, "Personal Transfer");
        // The SAME row, not a new one -- which is the whole reason no transactions table write is
        // needed: every transaction already points here by id.
        assertThat(renamed).isEqualTo(category);
        assertThat(string("SELECT is_system || '/' || icon || '/' || color FROM categories WHERE id = ?", renamed))
                .isEqualTo("true/users/orange");
    }

    @Test
    void aCategoryTheUserCreatedThemselvesIsLeftAlone() throws SQLException {
        UUID user = seedUser();
        // V123 skips a user who already had the name, leaving them a non-system row. V124 must not
        // reach back in and rename it -- it is theirs, and they can still rename or delete it.
        connection.createStatement().execute(
                "DELETE FROM categories WHERE user_id = '" + user + "' AND name = 'Paid a Person'");
        seedCategory(user, "paid a person", false);

        migrateTo("124");

        assertThat(string("SELECT name FROM categories WHERE user_id = ?", user)).isEqualTo("paid a person");
    }

    @Test
    void aUserWhoAlreadyHasPersonalTransferKeepsBothRows_ratherThanTheMigrationAborting()
            throws SQLException {
        UUID user = seedUser();
        seedCategory(user, "Personal Transfer", false);

        // Without the NOT EXISTS guard this violates uq_categories_user_name_ci, and a failed
        // migration does not degrade -- the backend does not boot.
        assertThatCode(() -> migrateTo("124")).doesNotThrowAnyException();

        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ? AND name = 'Paid a Person'", user))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM categories WHERE user_id = ? AND name = 'Personal Transfer'", user))
                .isEqualTo(1);
    }

    // --- helpers -----------------------------------------------------------------------------

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
        // V123 already ran, so seed the category the way registration would.
        seedCategory(userId, "Paid a Person", true);
        return userId;
    }

    private void seedCategory(UUID userId, String name, boolean isSystem) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO categories (id, user_id, name, is_system, icon, color) VALUES (?, ?, ?, ?, 'users', 'orange')")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, userId);
            ps.setString(3, name);
            ps.setBoolean(4, isSystem);
            ps.executeUpdate();
        }
    }

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
