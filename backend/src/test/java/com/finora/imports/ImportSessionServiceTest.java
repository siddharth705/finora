package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.ImportSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * ADR-0002 -- covers the actual safety properties persisted import sessions exist to provide:
 * a session can't be read/confirmed/deleted by anyone but its owner, an expired or
 * already-confirmed session can't be resumed, and expired sessions actually get cleaned up
 * (opportunistically, on the same user's next stage -- see the class's own doc comment for why
 * not a @Scheduled sweep).
 */
class ImportSessionServiceTest {

    private ImportSessionRepository importSessionRepository;
    private ImportSessionService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        importSessionRepository = mock(ImportSessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new ImportSessionService(importSessionRepository, objectMapper, new com.finora.imports.storage.StatementContentService(java.util.Optional.empty(), "", ""));
        when(importSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private StagedRow sampleRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private DetectedAccountInfo sampleDetected() {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, java.util.List.of(), null,
                null, null, null, null, null, null, null);
    }

    private ImportSession sessionOwnedBy(UUID owner, Instant expiresAt, String status) {
        ImportSession session = new ImportSession();
        org.springframework.test.util.ReflectionTestUtils.setField(session, "id", UUID.randomUUID());
        session.setUserId(owner);
        session.setFileName("statement.csv");
        session.setFileContent(new byte[]{1, 2, 3});
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(expiresAt);
        session.setStatus(status);
        return session;
    }

    @Test
    void createSession_persistsSerializedRowsAndDetectedAccount() {
        ImportSession created = service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getUserId()).isEqualTo(userId);
        assertThat(created.getStagedRowsJson()).contains("Coffee Shop");
        assertThat(created.getDetectedAccountJson()).contains("Test Bank");
        assertThat(created.getStatus()).isEqualTo(ImportSession.STATUS_STAGED);
        assertThat(created.getExpiresAt()).isAfter(Instant.now());
    }

    /**
     * BH-025 / BH-046. {@code storeContent} used to call {@code session.setFileContent(...)}
     * unconditionally right after {@code store().ifPresent(...)} recorded an address, so a
     * session created while object storage is configured still duplicated its bytes into
     * {@code file_content}. The fix: fill {@code file_content} only when {@code store()} came back
     * empty (no provider configured -- this test's {@code service}, wired with an empty storage
     * Optional in {@link #setUp}, is exactly that case and must keep behaving as before).
     */
    @Test
    void createSession_whenNoStorageProviderConfigured_stillFillsFileContent_unchangedFromBeforeTheFix() {
        byte[] fileBytes = {1, 2, 3};

        ImportSession created = service.createSession(userId, "statement.csv", fileBytes,
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getFileContent()).isEqualTo(fileBytes);
        assertThat(created.getObjectKey()).isNull();
        assertThat(created.getContentHash()).isNull();
    }

    /**
     * The other half of BH-025 / BH-046: with a provider configured, {@code store()} returns a
     * present address and {@code file_content} must be left null. Before the fix, this assertion
     * on {@code getFileContent()} would have failed -- the unconditional
     * {@code session.setFileContent(fileContent)} ran regardless of what {@code store()} returned.
     */
    @Test
    void createSession_whenObjectStorageConfigured_recordsTheAddress_andLeavesFileContentNull() {
        com.finora.imports.storage.StatementContentService storageBacked =
                mock(com.finora.imports.storage.StatementContentService.class);
        com.finora.imports.storage.ContentAddress address = new com.finora.imports.storage.ContentAddress(
                "c".repeat(64), "statements/cc/cc/" + "c".repeat(64) + ".bin");
        when(storageBacked.store(any())).thenReturn(Optional.of(address));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ImportSessionService storageBackedService =
                new ImportSessionService(importSessionRepository, objectMapper, storageBacked);

        ImportSession created = storageBackedService.createSession(userId, "statement.csv",
                new byte[]{1, 2, 3}, List.of(sampleRow()), sampleDetected());

        assertThat(created.getFileContent()).isNull();
        assertThat(created.getObjectKey()).isEqualTo(address.key());
        assertThat(created.getContentHash()).isEqualTo(address.hash());
    }

    /**
     * {@code createMultiSection} routes through the same {@code storeContent} as
     * {@code createSession} -- confirmed separately since it is the path actually exercised by a
     * multi-account BH-025 scenario (see {@code ImportServiceStorageDualWriteTest} for the
     * corresponding {@code confirmMultiSection} coverage on the persisted-row side).
     */
    @Test
    void createMultiSection_whenObjectStorageConfigured_alsoLeavesFileContentNull() {
        com.finora.imports.storage.StatementContentService storageBacked =
                mock(com.finora.imports.storage.StatementContentService.class);
        com.finora.imports.storage.ContentAddress address = new com.finora.imports.storage.ContentAddress(
                "d".repeat(64), "statements/dd/dd/" + "d".repeat(64) + ".bin");
        when(storageBacked.store(any())).thenReturn(Optional.of(address));
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ImportSessionService storageBackedService =
                new ImportSessionService(importSessionRepository, objectMapper, storageBacked);

        var section = new com.finora.dto.ImportDto.StagedAccountSection(
                sampleDetected(), List.of(sampleRow()), 1, 0, List.of());

        ImportSession created = storageBackedService.createMultiSection(userId, "composite.pdf",
                new byte[]{1, 2, 3}, List.of(section));

        assertThat(created.getFileContent()).isNull();
        assertThat(created.getObjectKey()).isEqualTo(address.key());
    }

    /**
     * BH-047. Creating a session performs no housekeeping at all -- asserted, not merely no longer
     * asserted against.
     *
     * <p>This used to sweep expired sessions as its first statement, inside the caller's
     * transaction. Those are other users' rows and {@code ImportSession} has no soft delete, so it
     * took real row locks on them and then held those locks across {@code storeContent}'s
     * object-storage write. A failure in the sweep rolled back the upload; a failed upload rolled
     * back the sweep. The sweep is a scheduled job now.
     */
    @Test
    void createSession_doesNoHousekeepingOfItsOwn() {
        service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        verify(importSessionRepository, never()).findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any());
        verify(importSessionRepository, never()).deleteAll(any());
    }

    /**
     * Cleanup is platform-wide now, not scoped to the acting user.
     *
     * <p>The previous assertion here was that another user's expired rows were deliberately NOT
     * touched. That scoping is exactly what left them forever: an expired session was only ever
     * deleted when THAT SAME user started another import, so anyone who imported once and did not
     * come back kept their row -- and the raw statement bytes on it -- indefinitely, with nothing
     * else ever removing them. The stated 48-hour retention did not hold for the population most
     * likely to trigger it.
     *
     * <p>The original concern behind the scoping is still honoured and is what the second
     * assertion pins: the query is bounded to a page, so this is a small index-ordered slice
     * rather than the "full-table scan every time anyone imports anything" it was avoiding.
     */
    @Test
    void sweepExpiredSessions_deletesExpiredSessions_regardlessOfWhoOwnsThem() {
        ImportSession someoneElsesExpired =
                sessionOwnedBy(otherUserId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(any(), any()))
                .thenReturn(List.of(someoneElsesExpired));

        // BH-047 moved WHERE this happens, not WHETHER it does. The assertion below is the one
        // this test has always made and is the point of the earlier user-scoping fix; it now names
        // the method that owns the behaviour instead of the upload it used to ride on.
        assertThat(service.sweepExpiredSessions()).isEqualTo(1);

        verify(importSessionRepository).deleteAll(List.of(someoneElsesExpired));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(importSessionRepository).findByExpiresAtBeforeOrderByExpiresAtAsc(any(), page.capture());
        assertThat(page.getValue().getPageSize()).isLessThanOrEqualTo(100);
    }

    @Test
    void getOwnedSession_rejectsSomeoneElsesSession() {
        ImportSession theirs = sessionOwnedBy(otherUserId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void getOwnedSession_rejectsAnExpiredSession() {
        ImportSession expired = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("expired");
    }

    /**
     * Carries ErrorCode.IMPORT_SESSION_ALREADY_CONFIRMED, checked here as its own assertion
     * rather than trusting the message-containing check above to also prove it -- the frontend
     * (ImportDetail.tsx's "Review this import" reaching an already-confirmed session, see
     * resumeSession's catch block in Import.tsx) branches on the CODE, not the message text, so a
     * regression that dropped the code while leaving the message unchanged would pass the
     * message-only assertion and still ship the exact bug this code exists to fix.
     */
    @Test
    void getOwnedSession_rejectsAnAlreadyConfirmedSession() {
        ImportSession confirmed = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_CONFIRMED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.getOwnedSession(userId, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been reviewed and confirmed")
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.IMPORT_SESSION_ALREADY_CONFIRMED);
    }

    @Test
    void getOwnedSession_returnsIt_whenOwnedUnexpiredAndStillStaged() {
        ImportSession valid = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(any())).thenReturn(Optional.of(valid));

        assertThat(service.getOwnedSession(userId, UUID.randomUUID())).isEqualTo(valid);
    }

    @Test
    void listActiveSessions_excludesExpiredOnesEvenIfStillMarkedStaged() {
        // Belt-and-suspenders against the opportunistic cleanup not having run yet for this user
        // -- listActiveSessions() shouldn't show a session as "resumable" just because nothing
        // has deleted it yet.
        ImportSession stillValid = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        ImportSession notYetCleanedUp = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED))
                .thenReturn(List.of(stillValid, notYetCleanedUp));

        List<ImportSession> active = service.listActiveSessions(userId);

        assertThat(active).containsExactly(stillValid);
    }

    /**
     * The actual regression this method exists to prevent: a user with BOTH kinds staged used to
     * get an exception for their entire {@code GET /import/sessions} response, because
     * {@code listActiveSessions} included the MULTI_ACCOUNT session and the controller's
     * {@code toSummary} unconditionally called {@link ImportSessionService#readStagedRows}, which
     * {@code requireKind}s SINGLE_ACCOUNT. {@link ImportSessionService#listResumableSessions}
     * filters the MULTI_ACCOUNT session out before any caller can make that mistake.
     */
    @Test
    void listResumableSessions_excludesMultiAccountSessions_keepingSingleAccountOnes() {
        ImportSession singleAccount = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        ImportSession multiAccount = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        multiAccount.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        when(importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED))
                .thenReturn(List.of(singleAccount, multiAccount));

        List<ImportSession> resumable = service.listResumableSessions(userId);

        assertThat(resumable).containsExactly(singleAccount);
    }

    /** {@code supportsResume}'s fail-closed default: a session kind neither branch recognizes is
     *  excluded (not resumable) rather than thrown on -- the whole point of moving away from the
     *  original equality-based filter is to not reproduce its failure mode (one session's kind
     *  breaking the entire list) for whatever kind comes after MULTI_ACCOUNT. */
    @Test
    void listResumableSessions_excludesAnUnrecognizedSessionKind_insteadOfThrowing() {
        ImportSession unknownKind = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        unknownKind.setSessionKind("SOME_FUTURE_KIND");
        when(importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED))
                .thenReturn(List.of(unknownKind));

        List<ImportSession> resumable = service.listResumableSessions(userId);

        assertThat(resumable).isEmpty();
    }

    @Test
    void claimForConfirmation_flipsStatusAtomically_whenSessionIsStillStaged() {
        UUID sessionId = UUID.randomUUID();
        ImportSession staged = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        ImportSession nowConfirmed = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_CONFIRMED);
        when(importSessionRepository.findById(sessionId)).thenReturn(Optional.of(staged), Optional.of(nowConfirmed));
        when(importSessionRepository.claimForConfirmation(sessionId)).thenReturn(1);

        ImportSession result = service.claimForConfirmation(userId, sessionId);

        assertThat(result.getStatus()).isEqualTo(ImportSession.STATUS_CONFIRMED);
        verify(importSessionRepository).claimForConfirmation(sessionId);
    }

    @Test
    void claimForConfirmation_rejectsALostRace_whenTheAtomicUpdateAffectsZeroRows() {
        // This is the actual scenario the atomic UPDATE exists for: getOwnedSession() above
        // legitimately saw STAGED (a concurrent request hadn't committed yet), but by the time
        // the atomic UPDATE runs, that other request already flipped it to CONFIRMED. A bare
        // read-then-save wouldn't catch this -- the whole point of claimForConfirmation() is that
        // it does.
        UUID sessionId = UUID.randomUUID();
        ImportSession staged = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findById(sessionId)).thenReturn(Optional.of(staged));
        when(importSessionRepository.claimForConfirmation(sessionId)).thenReturn(0);

        assertThatThrownBy(() -> service.claimForConfirmation(userId, sessionId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already been confirmed");
    }

    @Test
    void readStagedRows_roundTripsWhatCreateSessionWrote() {
        ImportSession session = new ImportSession();
        session.setStagedRowsJson("[{\"date\":\"2026-07-01\",\"description\":\"Coffee Shop\",\"amount\":150.00,"
                + "\"type\":\"EXPENSE\",\"suggestedCategory\":\"Food & Dining\",\"categorySource\":\"rule\","
                + "\"ruleId\":null,\"likelyDuplicate\":false}]");

        List<StagedRow> rows = service.readStagedRows(session);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).description()).isEqualTo("Coffee Shop");
        assertThat(rows.get(0).amount()).isEqualByComparingTo("150.00");
    }
}
