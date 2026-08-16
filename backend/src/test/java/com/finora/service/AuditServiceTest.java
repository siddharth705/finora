package com.finora.service;

import com.finora.entity.AuditLog;
import com.finora.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * BH-044's redaction sweep, decision logic in isolation -- everything a mocked repository can
 * prove: which candidates get redacted, which get skipped and why, and that a per-row save failure
 * does not abort the batch. An integration test against real Postgres covers what only real JSONB
 * and a real partial index can: {@code AuditServiceRedactionIT}.
 */
class AuditServiceTest {

    private AuditLogRepository auditLogRepository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        service = new AuditService(auditLogRepository);
        ReflectionTestUtils.setField(service, "redactionEnabled", true);
        ReflectionTestUtils.setField(service, "retentionDays", 730);
        ReflectionTestUtils.setField(service, "redactionBatchSize", 500);
        // save() mirrors a real repository: returns whatever it was handed, so mutations made by
        // the service before calling save() are what tests observe afterward.
        when(auditLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Default: every candidate is still eligible when the fresh re-check runs. Tests proving
        // the re-check actually catches something override this for that one candidate's id.
        when(auditLogRepository.existsByIdAndRedactedAtIsNull(any())).thenReturn(true);
    }

    /** A realistic candidate: real financial content in metadata, the shape this sweep exists to
     *  wipe. createdAt has no public setter -- AuditLog is meant to be timestamped at write time
     *  only -- so it is backdated via reflection, the same pattern GlobalAuditLogIT already uses. */
    private static AuditLog candidateWithFinancialMetadata(Instant createdAt) {
        AuditLog entry = new AuditLog();
        ReflectionTestUtils.setField(entry, "id", UUID.randomUUID());
        entry.setUserId(UUID.randomUUID());
        entry.setAction("TRANSACTION_DELETED");
        entry.setEntityType("Transaction");
        entry.setEntityId(UUID.randomUUID());
        entry.setRequestId("req-" + UUID.randomUUID());
        entry.setMetadata(Map.of("amount", "4200.00", "description", "Rent payment to landlord"));
        ReflectionTestUtils.setField(entry, "createdAt", createdAt);
        return entry;
    }

    @Test
    void redactExpiredMetadata_wipesMetadataAndStampsRedactedAt_forAnExpiredCandidate() {
        AuditLog old = candidateWithFinancialMetadata(Instant.now().minus(800, ChronoUnit.DAYS));
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(old));

        AuditService.RedactionResult result = service.redactExpiredMetadata();

        assertThat(result.redacted()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(old.getMetadata()).isEqualTo(Map.of("redacted", true));
        assertThat(old.getRedactedAt()).isNotNull();
        verify(auditLogRepository).save(old);
    }

    @Test
    void redactExpiredMetadata_preservesEveryOtherField_whenRedacting() {
        UUID userId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Instant createdAt = Instant.now().minus(800, ChronoUnit.DAYS);
        AuditLog old = candidateWithFinancialMetadata(createdAt);
        old.setUserId(userId);
        old.setEntityId(entityId);
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(old));

        service.redactExpiredMetadata();

        assertThat(old.getUserId()).isEqualTo(userId);
        assertThat(old.getAction()).isEqualTo("TRANSACTION_DELETED");
        assertThat(old.getEntityType()).isEqualTo("Transaction");
        assertThat(old.getEntityId()).isEqualTo(entityId);
        assertThat(old.getCreatedAt()).isEqualTo(createdAt);
    }

    /**
     * The discovery query's own {@code redactedAt IS NULL} filter means the candidate it returns
     * always looks unredacted in memory -- so the case this test proves is the fresh, live re-check
     * {@link AuditLogRepository#existsByIdAndRedactedAtIsNull} performs immediately before mutating,
     * NOT a property of the in-memory candidate object itself. Realistic trigger: a second app
     * instance's redaction pass reached this same row first, between this instance's discovery
     * query and its per-row processing (Railway can run more than one instance;
     * {@code fixedDelay} only prevents overlap within one JVM) -- simulated here by having the
     * fresh re-check report the row no longer eligible, exactly what a real concurrent redaction
     * would cause it to report.
     */
    @Test
    void redactExpiredMetadata_doesNotReprocessARowThatIsAlreadyRedacted() {
        AuditLog wonTheRaceElsewhere = candidateWithFinancialMetadata(Instant.now().minus(800, ChronoUnit.DAYS));
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(wonTheRaceElsewhere));
        when(auditLogRepository.existsByIdAndRedactedAtIsNull(wonTheRaceElsewhere.getId())).thenReturn(false);

        AuditService.RedactionResult result = service.redactExpiredMetadata();

        assertThat(result.redacted()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void redactExpiredMetadata_continuesTheBatch_whenOneRowFailsToSave() {
        AuditLog first = candidateWithFinancialMetadata(Instant.now().minus(800, ChronoUnit.DAYS));
        AuditLog second = candidateWithFinancialMetadata(Instant.now().minus(800, ChronoUnit.DAYS));
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(first, second));
        when(auditLogRepository.save(first)).thenThrow(new RuntimeException("boom"));

        AuditService.RedactionResult result = service.redactExpiredMetadata();

        assertThat(result.redacted()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        verify(auditLogRepository).save(first);
        verify(auditLogRepository).save(second);
        // The failed row's own state was still mutated in memory before save() threw, but since
        // save() never returned, redactedAt staying set here is irrelevant -- what matters is the
        // row was never actually persisted as redacted, so the next pass's query (createdAt <
        // cutoff AND redactedAt IS NULL, evaluated against the DATABASE row, not this in-memory
        // one) will surface it again.
        assertThat(second.getMetadata()).isEqualTo(Map.of("redacted", true));
    }

    @Test
    void redactExpiredMetadata_passesTheConfiguredBatchSizeAndAnAtLeast730DayOldCutoff_toTheDiscoveryQuery() {
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        service.redactExpiredMetadata();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(auditLogRepository).findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(
                cutoffCaptor.capture(), pageableCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(729, ChronoUnit.DAYS));
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(500);
    }

    /**
     * {@link AuditService#MINIMUM_RETENTION}. Even a misconfigured retention-days of 0 must not
     * make the cutoff "now" -- a row written moments ago must never become a redaction candidate,
     * regardless of what the property says, because unlike BH-017's storage sweep this mistake is
     * not recoverable: the real financial content is gone the moment it is overwritten.
     */
    @Test
    void redactExpiredMetadata_enforcesA90DaySafetyFloor_evenIfRetentionDaysIsMisconfiguredToZero() {
        ReflectionTestUtils.setField(service, "retentionDays", 0);
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());

        service.redactExpiredMetadata();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogRepository).findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(
                cutoffCaptor.capture(), any());
        assertThat(cutoffCaptor.getValue()).isBefore(Instant.now().minus(89, ChronoUnit.DAYS));
    }

    @Test
    void scheduledRedaction_doesNothing_whenDisabled() {
        ReflectionTestUtils.setField(service, "redactionEnabled", false);

        service.scheduledRedaction();

        verifyNoInteractions(auditLogRepository);
    }

    @Test
    void scheduledRedaction_delegatesToRedactExpiredMetadata_whenEnabled() {
        AuditLog old = candidateWithFinancialMetadata(Instant.now().minus(800, ChronoUnit.DAYS));
        when(auditLogRepository.findByCreatedAtBeforeAndRedactedAtIsNullOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of(old));

        service.scheduledRedaction();

        verify(auditLogRepository).save(old);
    }

    @Test
    void record_isUnaffectedByTheRedactionFieldsAddedToThisClass() {
        UUID userId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();

        service.record(userId, "USER_LOGIN", "User", entityId, Map.of("ip", "127.0.0.1"));

        ArgumentCaptor<AuditLog> savedCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getUserId()).isEqualTo(userId);
        assertThat(savedCaptor.getValue().getAction()).isEqualTo("USER_LOGIN");
        assertThat(savedCaptor.getValue().getRedactedAt()).isNull();
    }
}
