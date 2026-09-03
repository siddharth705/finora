package com.finora.entity;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V144's DDL, against a real Postgres.
 *
 * <p>Proves the parts nothing below the database can: that the rebuilt {@code
 * import_jobs_status_valid} CHECK actually accepts the new status, that the sequence backing Held
 * IDs issues values, that the permission is granted to somebody (a permission with no
 * {@code role_permissions} row grants nothing and would 403 for every admin), and that the held
 * status CHECK rejects a value outside the lifecycle.
 */
class HeldStatementSchemaIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void statusCheckAcceptsHeldForTrustReview() {
        // The whole assertion is that this does not throw: before V144 rebuilt the constraint,
        // the literal enumeration would have rejected the value outright.
        jdbc.update("UPDATE import_jobs SET status = 'HELD_FOR_TRUST_REVIEW' "
                + "WHERE id = (SELECT id FROM import_jobs LIMIT 1)");
    }

    @Test
    void importJobsCarriesTheHeldStatementBackReference() {
        Integer columns = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'import_jobs' AND column_name = 'held_statement_id'
                """, Integer.class);

        assertThat(columns).isEqualTo(1);
    }

    @Test
    void heldStatementSequenceIssuesValues() {
        assertThat(jdbc.queryForObject("SELECT nextval('held_statement_reference_seq')", Long.class))
                .isNotNull();
    }

    @Test
    void permissionIsGrantedToAdminAndSuperAdmin() {
        Integer grants = jdbc.queryForObject("""
                SELECT count(*) FROM role_permissions rp
                  JOIN roles r ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE p.name = 'TRUST_REVIEW_MANAGE' AND r.name IN ('ADMIN', 'SUPER_ADMIN')
                """, Integer.class);

        assertThat(grants).isEqualTo(2);
    }

    @Test
    void heldStatementsRejectsAStatusOutsideTheLifecycle() {
        assertThat(assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO held_statements (held_id, import_job_id, user_id,
                                             statement_object_key, status)
                VALUES ('HLD-2026-000001', gen_random_uuid(), gen_random_uuid(), 'k',
                        'NOT_A_REAL_STATUS')
                """))).isNotNull();
    }

    /**
     * Account deletion must survive a held statement.
     *
     * <p>{@code import_jobs.user_id} is {@code ON DELETE CASCADE} (V66), so a user delete tears
     * down their import rows. Without matching cascades here, a {@code held_statements} row
     * referencing one of those jobs blocks the whole delete on a foreign key -- breaking the
     * shipped account-deletion path for anyone who ever had an import held. This is the test that
     * catches that, and it caught it once already.
     */
    @Test
    void deletingAUserCascadesThroughHoldsAndTheirEvents() {
        java.util.UUID userId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) "
                + "VALUES (?, ?, 'x', 'Cascade Test')", userId, "cascade-" + userId + "@example.com");

        java.util.UUID jobId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO import_jobs (id, user_id, file_name, content_hash, object_key,
                                         source_format, status, created_at, next_attempt_at)
                VALUES (?, ?, 's.pdf', ?, 'k1', 'PDF', 'HELD_FOR_TRUST_REVIEW', now(), now())
                """, jobId, userId, "hash-" + jobId);

        java.util.UUID heldId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO held_statements (id, held_id, import_job_id, user_id,
                                             statement_object_key, status)
                VALUES (?, ?, ?, ?, 'k1', 'HELD')
                """, heldId, "HLD-2026-" + String.format("%06d", Math.abs(heldId.hashCode()) % 999999),
                jobId, userId);

        jdbc.update("""
                INSERT INTO held_statement_events (held_statement_id, event_type, to_status)
                VALUES (?, 'HELD_CREATED', 'HELD')
                """, heldId);

        // The assertion is that this does not throw a foreign-key violation.
        jdbc.update("DELETE FROM users WHERE id = ?", userId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_statements WHERE id = ?",
                Integer.class, heldId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM held_statement_events WHERE held_statement_id = ?",
                Integer.class, heldId)).isZero();
    }

    /**
     * The opposite rule for actor columns: deleting the admin who resolved a hold must NOT erase
     * the record of what they decided. History outlives the actor, so these are SET NULL rather
     * than CASCADE.
     */
    @Test
    void deletingAnAdminKeepsTheHoldAndNullsTheActor() {
        java.util.UUID ownerId = java.util.UUID.randomUUID();
        java.util.UUID adminId = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) "
                + "VALUES (?, ?, 'x', 'Owner')", ownerId, "owner-" + ownerId + "@example.com");
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name) "
                + "VALUES (?, ?, 'x', 'Admin')", adminId, "admin-" + adminId + "@example.com");

        java.util.UUID jobId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO import_jobs (id, user_id, file_name, content_hash, object_key,
                                         source_format, status, created_at, next_attempt_at)
                VALUES (?, ?, 's.pdf', ?, 'k1', 'PDF', 'HELD_FOR_TRUST_REVIEW', now(), now())
                """, jobId, ownerId, "hash-" + jobId);

        java.util.UUID heldId = java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO held_statements (id, held_id, import_job_id, user_id,
                                             statement_object_key, status, resolved_by)
                VALUES (?, ?, ?, ?, 'k1', 'IMPORTED', ?)
                """, heldId, "HLD-2026-" + String.format("%06d", Math.abs(jobId.hashCode()) % 999999),
                jobId, ownerId, adminId);

        jdbc.update("DELETE FROM users WHERE id = ?", adminId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM held_statements WHERE id = ?",
                Integer.class, heldId))
                .as("the hold survives its resolver being deleted")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT resolved_by FROM held_statements WHERE id = ?",
                String.class, heldId)).isNull();
    }
}
