package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * BH-044's redaction sweep against a real Postgres. {@link AuditServiceTest} proves the decision
 * logic works given whatever a mock hands it; this proves the real thing: that {@code
 * findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc} (backed by V89's partial index)
 * finds the right candidates, and that a real JSONB round-trip through Hibernate actually replaces
 * financial content with the marker rather than merely mutating an in-memory object.
 *
 * <p>{@code app.audit.redaction.enabled} is off in {@code application-test.yml} (BH-058 -- a
 * background thread rewriting audit rows mid-test would be cross-test pollution), so these tests
 * call {@link AuditService#redactExpiredMetadata()} directly, bypassing {@code @Scheduled}, the
 * same way {@code StatementStorageSweepServiceIT} bypasses BH-017's sweep scheduler.
 *
 * <p>Every test method is individually {@code @Transactional}, matching {@code
 * StatementStorageSweepServiceIT}'s own per-method convention (not a class-level annotation --
 * every other {@code *IT.java} file in this repo does it per-method too): it both gives free
 * per-test cleanup (Spring rolls the transaction back at test end) and lets fixtures use {@link
 * EntityManager} directly for the one thing plain repository calls can't do -- see {@link
 * #newEntry} for why {@code createdAt} specifically needs that.
 */
class AuditServiceRedactionIT extends AbstractIntegrationTest {

    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    // Deliberately NOT @Autowired: the real AuditService bean is a live @Scheduled singleton, and
    // AbstractIntegrationTest caches ONE ApplicationContext across the whole IT suite. Flipping
    // redactionEnabled to true on that shared bean (with no matching teardown to flip it back)
    // would re-enable the real background sweep for every other IT test in the same JVM run --
    // the exact BH-058 cross-test-pollution class of bug application-test.yml's
    // app.audit.redaction.enabled: false exists to prevent. Constructing our own instance,
    // same precedent StatementStorageSweepServiceIT already establishes, sidesteps this
    // entirely: an unmanaged instance is never touched by Spring's scheduler, so there is
    // nothing to leak regardless of what its fields are set to or whether a test fails before
    // resetting them.
    private AuditService auditService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        auditService = new AuditService(auditLogRepository);
        // A configured retention window well above AuditService.MINIMUM_RETENTION (90 days) but
        // small enough that ordinary test fixture ages (well under a year) can straddle it clearly.
        ReflectionTestUtils.setField(auditService, "retentionDays", 100);
        ReflectionTestUtils.setField(auditService, "redactionBatchSize", 500);
        ReflectionTestUtils.setField(auditService, "redactionEnabled", true);

        User user = new User();
        user.setEmail("audit-redaction-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Audit Redaction Test User");
        userId = userRepository.save(user).getId();
    }

    /**
     * {@code createdAt} has no public setter, deliberately -- entries are meant to be timestamped
     * at write time only, never backdated in production code. Set via reflection BEFORE the entity
     * is ever saved, not after: the column is {@code updatable = false}
     * ({@code AuditLog.createdAt}'s own {@code @Column}), so a reflection edit followed by
     * {@code save()} on an ALREADY-persisted row would silently do nothing -- Hibernate omits
     * {@code updatable = false} columns from every UPDATE it generates, INSERT only. Setting the
     * field on a still-transient entity puts the backdated value into the INSERT itself, which is
     * not restricted. Same technique {@code GlobalAuditLogIT.saveAuditEntry} already uses.
     */
    private AuditLog newEntry(String action, String entityType, Map<String, Object> metadata, Instant createdAt) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(UUID.randomUUID());
        entry.setRequestId("req-" + UUID.randomUUID());
        entry.setMetadata(metadata);
        ReflectionTestUtils.setField(entry, "createdAt", createdAt);
        return entry;
    }

    private AuditLog saveAuditEntry(String action, Map<String, Object> metadata, Instant createdAt) {
        return auditLogRepository.save(newEntry(action, "Transaction", metadata, createdAt));
    }

    /** Forces a genuine round-trip: without this, a re-fetch inside the same transaction could be
     *  satisfied from Hibernate's first-level cache rather than actually reading back what got
     *  persisted, which is exactly the JSONB-serialization behaviour these tests exist to check. */
    private AuditLog reload(UUID id) {
        entityManager.flush();
        entityManager.clear();
        return auditLogRepository.findById(id).orElseThrow();
    }

    @Test
    @Transactional
    void redactExpiredMetadata_wipesMetadataOfARowOlderThanRetention_againstRealPostgres() {
        Map<String, Object> realFinancialMetadata =
                Map.of("amount", "4200.00", "description", "Rent payment to landlord");
        AuditLog old = saveAuditEntry("TRANSACTION_DELETED", realFinancialMetadata,
                Instant.now().minus(150, ChronoUnit.DAYS));
        entityManager.flush();
        entityManager.clear();

        AuditService.RedactionResult result = auditService.redactExpiredMetadata();

        assertThat(result.redacted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        AuditLog reloaded = reload(old.getId());
        assertThat(reloaded.getMetadata()).isEqualTo(AuditService.REDACTED_METADATA);
        assertThat(reloaded.getMetadata()).doesNotContainKeys("amount", "description");
        assertThat(reloaded.getRedactedAt()).isNotNull();
    }

    @Test
    @Transactional
    void redactExpiredMetadata_preservesTheEventFieldsExactly_onARedactedRow() {
        UUID entityId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(150, ChronoUnit.DAYS);
        AuditLog entry = newEntry("BUDGET_UPSERTED", "Budget", Map.of("limit", "50000.00"), createdAt);
        entry.setEntityId(entityId);
        entry.setRequestId("req-fixed-marker");
        AuditLog saved = auditLogRepository.save(entry);
        entityManager.flush();
        entityManager.clear();

        auditService.redactExpiredMetadata();

        AuditLog reloaded = reload(saved.getId());
        assertThat(reloaded.getAction()).isEqualTo("BUDGET_UPSERTED");
        assertThat(reloaded.getEntityType()).isEqualTo("Budget");
        assertThat(reloaded.getEntityId()).isEqualTo(entityId);
        assertThat(reloaded.getUserId()).isEqualTo(userId);
        assertThat(reloaded.getRequestId()).isEqualTo("req-fixed-marker");
        // Truncated to the precision Postgres TIMESTAMPTZ actually stores (microseconds), same
        // caveat every other createdAt round-trip assertion in this codebase works around.
        assertThat(reloaded.getCreatedAt()).isCloseTo(createdAt, within(1, ChronoUnit.SECONDS));
        // The point of this test: metadata is the ONLY thing that changed.
        assertThat(reloaded.getMetadata()).isEqualTo(AuditService.REDACTED_METADATA);
    }

    @Test
    @Transactional
    void redactExpiredMetadata_doesNotTouchARowWithinTheRetentionWindow() {
        Map<String, Object> recentMetadata = Map.of("amount", "15.50", "description", "Coffee");
        AuditLog recent = saveAuditEntry("TRANSACTION_CREATED", recentMetadata,
                Instant.now().minus(10, ChronoUnit.DAYS));
        entityManager.flush();
        entityManager.clear();

        AuditService.RedactionResult result = auditService.redactExpiredMetadata();

        assertThat(result.redacted()).isZero();
        AuditLog reloaded = reload(recent.getId());
        assertThat(reloaded.getMetadata()).isEqualTo(recentMetadata);
        assertThat(reloaded.getRedactedAt()).isNull();
    }

    @Test
    @Transactional
    void redactExpiredMetadata_doesNotReprocessARowAlreadyRedacted() {
        Instant alreadyRedactedAt = Instant.now().minus(5, ChronoUnit.DAYS);
        AuditLog entry = newEntry("TRANSACTION_DELETED", "Transaction",
                Map.of("redacted", true), Instant.now().minus(150, ChronoUnit.DAYS));
        entry.setRedactedAt(alreadyRedactedAt);
        AuditLog saved = auditLogRepository.save(entry);
        entityManager.flush();
        entityManager.clear();

        AuditService.RedactionResult result = auditService.redactExpiredMetadata();

        assertThat(result.redacted()).isZero();
        AuditLog reloaded = reload(saved.getId());
        assertThat(reloaded.getRedactedAt())
                .as("an already-redacted row's redactedAt must not be overwritten by a later pass")
                .isCloseTo(alreadyRedactedAt, within(1, ChronoUnit.SECONDS));
        assertThat(reloaded.getMetadata()).isEqualTo(Map.of("redacted", true));
    }

    @Test
    @Transactional
    void redactExpiredMetadata_handlesAMixOfOldRecentAndAlreadyRedactedRowsInOnePass() {
        AuditLog old1 = saveAuditEntry("TRANSACTION_DELETED",
                Map.of("amount", "100.00"), Instant.now().minus(200, ChronoUnit.DAYS));
        AuditLog old2 = saveAuditEntry("BUDGET_UPSERTED",
                Map.of("limit", "9000.00"), Instant.now().minus(120, ChronoUnit.DAYS));
        AuditLog recent = saveAuditEntry("TRANSACTION_CREATED",
                Map.of("amount", "42.00"), Instant.now().minus(5, ChronoUnit.DAYS));
        AuditLog alreadyRedactedEntry = newEntry("TRANSACTION_DELETED", "Transaction",
                Map.of("redacted", true), Instant.now().minus(300, ChronoUnit.DAYS));
        alreadyRedactedEntry.setRedactedAt(Instant.now().minus(1, ChronoUnit.DAYS));
        AuditLog alreadyRedacted = auditLogRepository.save(alreadyRedactedEntry);
        entityManager.flush();
        entityManager.clear();

        AuditService.RedactionResult result = auditService.redactExpiredMetadata();

        assertThat(result.redacted()).isEqualTo(2);
        assertThat(result.skipped()).isZero(); // already-redacted row isn't even a query candidate
        assertThat(reload(old1.getId()).getMetadata()).isEqualTo(AuditService.REDACTED_METADATA);
        assertThat(reload(old2.getId()).getMetadata()).isEqualTo(AuditService.REDACTED_METADATA);
        assertThat(reload(recent.getId()).getMetadata()).isEqualTo(Map.of("amount", "42.00"));
        assertThat(reload(recent.getId()).getRedactedAt()).isNull();
        assertThat(reload(alreadyRedacted.getId()).getMetadata()).isEqualTo(Map.of("redacted", true));
    }
}
