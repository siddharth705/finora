package com.finora.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.config.BuildVersionResolver;
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
    private BuildVersionResolver buildVersionResolver;
    private ImportSessionService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        importSessionRepository = mock(ImportSessionRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        buildVersionResolver = mock(BuildVersionResolver.class);
        // Every EXISTING test in this class (including all the Phase 1A dedup-window tests) was
        // written against a world with no version concept at all -- defaulting to null here makes
        // them exercise the fallback-window path Phase 1B preserves for that case, unchanged,
        // rather than silently starting to exercise the new version-comparison path instead.
        when(buildVersionResolver.currentCommit()).thenReturn(null);
        service = new ImportSessionService(importSessionRepository, objectMapper, buildVersionResolver);
        when(importSessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private StagedRow sampleRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private DetectedAccountInfo sampleDetected() {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null, null, null, null,
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

    private com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence sampleCreditCardSummary() {
        return new com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence(
                new BigDecimal("10000"), new BigDecimal("2450.75"), BigDecimal.ZERO, new BigDecimal("50"),
                new BigDecimal("10000"), new BigDecimal("12450.75"),
                com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.ExtractionMethod.GRID,
                List.of());
    }

    @Test
    void createSession_withACreditCardSummary_persistsAndReadsItBack() {
        ImportSession created = service.createSession(userId, "statement.pdf", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected(), null, sampleCreditCardSummary());

        assertThat(created.getCreditCardSummaryJson()).contains("12450.75");
        assertThat(service.readCreditCardSummary(created)).isEqualTo(sampleCreditCardSummary());
    }

    @Test
    void createSession_withNoCreditCardSummary_leavesTheColumnNull() {
        ImportSession created = service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getCreditCardSummaryJson()).isNull();
        assertThat(service.readCreditCardSummary(created))
                .isEqualTo(com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE);
    }

    @Test
    void createSession_withCreditCardSummaryEvidenceNone_alsoLeavesTheColumnNull() {
        // NONE is never null itself (see that constant's own doc comment) -- confirms the "not
        // NONE either" check actually fires, not just the plain-null one above.
        ImportSession created = service.createSession(userId, "statement.pdf", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected(), null,
                com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE);

        assertThat(created.getCreditCardSummaryJson()).isNull();
    }

    @Test
    void createMultiSection_withACreditCardSummary_persistsIt() {
        var sections = List.of(new com.finora.dto.ImportDto.StagedAccountSection(
                sampleDetected(), List.of(sampleRow()), 1, 0, List.of()));

        ImportSession created = service.createMultiSection(userId, "statement.pdf", new byte[]{1, 2, 3},
                sections, null, sampleCreditCardSummary());

        assertThat(created.getCreditCardSummaryJson()).contains("12450.75");
    }

    /**
     * Storage review lifecycle change: staging ALWAYS writes to temporary (database) storage now,
     * regardless of whether a storage provider is configured -- object storage is not reached
     * until the user confirms (see {@code ImportSessionService.storeContent}'s own doc comment).
     * This used to be conditional (BH-025/BH-046: {@code file_content} filled only when no
     * provider was configured, left null with {@code object_key} set otherwise); that branch is
     * gone from {@code storeContent} entirely, so there is nothing left to test per-provider-state
     * -- one behaviour, unconditionally.
     *
     * <p>{@code contentHash} is still always computed (V79 / distributed-resilience-patterns-
     * audit-2026-08-14.md §3 -- {@link ImportSessionService#findLiveSessionByContentHash}
     * deduplicates on it), independent of object storage entirely.
     */
    @Test
    void createSession_alwaysFillsFileContent_andNeverSetsAnObjectKey() {
        byte[] fileBytes = {1, 2, 3};

        ImportSession created = service.createSession(userId, "statement.csv", fileBytes,
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getFileContent()).isEqualTo(fileBytes);
        assertThat(created.getObjectKey()).isNull();
        assertThat(created.getContentHash()).isEqualTo(com.finora.imports.storage.ContentAddress.hashOf(fileBytes));
    }

    /**
     * {@code createMultiSection} routes through the same {@code storeContent} as
     * {@code createSession} -- confirmed separately since it is the path a multi-account upload
     * actually exercises.
     */
    @Test
    void createMultiSection_alsoAlwaysFillsFileContent() {
        var section = new com.finora.dto.ImportDto.StagedAccountSection(
                sampleDetected(), List.of(sampleRow()), 1, 0, List.of());

        ImportSession created = service.createMultiSection(userId, "composite.pdf",
                new byte[]{1, 2, 3}, List.of(section));

        assertThat(created.getFileContent()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(created.getObjectKey()).isNull();
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
        // Names the sweep's own query, which now also excludes sessions an open trust review
        // still depends on -- see HeldSessionSurvivesCleanupIT for why that exemption exists.
        when(importSessionRepository.findSweepableExpiredSessions(any(), any(), any()))
                .thenReturn(List.of(someoneElsesExpired));

        // BH-047 moved WHERE this happens, not WHETHER it does. The assertion below is the one
        // this test has always made and is the point of the earlier user-scoping fix; it now names
        // the method that owns the behaviour instead of the upload it used to ride on.
        assertThat(service.sweepExpiredSessions()).isEqualTo(1);

        verify(importSessionRepository).deleteAll(List.of(someoneElsesExpired));

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(importSessionRepository).findSweepableExpiredSessions(any(), any(), page.capture());
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

    @Test
    void findLiveSessionByContentHash_returnsAnUnexpiredMatch() {
        ImportSession match = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-a", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(match));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-a");

        assertThat(found).contains(match);
        verify(importSessionRepository, never()).delete(any());
    }

    /**
     * A partial unique index can't express "and not expired" (its predicate must be immutable, so
     * it can't reference now()) -- this is the application-level half that handles it instead. A
     * STAGED session that expired but hasn't been swept yet must not block a genuinely new upload
     * of the same statement with a false duplicate, so it's deleted here rather than returned as a
     * match.
     */
    @Test
    void findLiveSessionByContentHash_deletesAnExpiredMatch_andReportsNoneFound() {
        ImportSession expired = sessionOwnedBy(userId, Instant.now().minusSeconds(60), ImportSession.STATUS_STAGED);
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-b", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(expired));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-b");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(expired);
    }

    @Test
    void findLiveSessionByContentHash_returnsEmpty_whenNoStagedSessionHasThisHash() {
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-c", ImportSession.STATUS_STAGED)).thenReturn(Optional.empty());

        assertThat(service.findLiveSessionByContentHash(userId, "hash-c")).isEmpty();
        verify(importSessionRepository, never()).delete(any());
    }

    /**
     * The actual bug this method exists to fix (2026-08-31): a session created minutes-to-hours
     * ago, still well within its 48h TTL, is NOT the double-click/retry case this dedup check was
     * built for -- it's a genuinely later re-upload, and by then the parser that produced its
     * staged rows may have been fixed. Confirmed against a real HDFC statement: a stale session
     * kept replaying a 12-row result on every re-upload even after the parser was fixed to
     * correctly extract all 243 rows. A session must be recent, not merely unexpired, to be
     * replayed automatically.
     */
    @Test
    void findLiveSessionByContentHash_deletesAStaleButUnexpiredMatch_andReportsNoneFound() {
        ImportSession stale = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                stale, "createdAt", Instant.now().minus(java.time.Duration.ofMinutes(10)));
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-d", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(stale));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-d");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(stale);
    }

    /**
     * The dedup protection this method exists FOR must still work: a session created moments ago
     * (a double-click, or a client retrying a request whose response was lost) is still returned
     * as a match, not treated as stale.
     */
    @Test
    void findLiveSessionByContentHash_stillReturnsAVeryRecentMatch_withinTheDedupWindow() {
        ImportSession justCreated = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                justCreated, "createdAt", Instant.now().minusSeconds(5));
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-e", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(justCreated));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-e");

        assertThat(found).contains(justCreated);
        verify(importSessionRepository, never()).delete(any());
    }

    /**
     * Phase 1B: the actual fix for the gap Phase 1A's window still left open -- a session created
     * moments before a parser fix deploys was still replayed for the rest of that 5-minute window.
     * Version comparison has no such gap: a mismatch is a mismatch regardless of age.
     */
    @Test
    void findLiveSessionByContentHash_deletesAVersionMismatchedMatch_evenIfCreatedSecondsAgo() {
        when(buildVersionResolver.currentCommit()).thenReturn("newcommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(session, "createdAt", Instant.now());
        session.setParserVersion("oldcommit");
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-f", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-f");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(session);
    }

    /**
     * A session staged before this feature shipped has parserVersion == null, which must never
     * equal a real resolved commit -- otherwise every pre-existing staged session would look like
     * it matches the current build by coincidence of both being "unset".
     */
    @Test
    void findLiveSessionByContentHash_treatsANullStoredVersion_asAMismatch() {
        when(buildVersionResolver.currentCommit()).thenReturn("newcommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(session, "createdAt", Instant.now());
        // parserVersion left null -- the default for a session built via sessionOwnedBy.
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-g", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-g");

        assertThat(found).isEmpty();
        verify(importSessionRepository).delete(session);
    }

    /**
     * The other half: when the version DOES match, the session replays even if it's old (though
     * still unexpired) -- proving version comparison, not age, is now the real freshness signal
     * whenever a version can be resolved at all.
     */
    @Test
    void findLiveSessionByContentHash_returnsAVersionMatchedMatch_evenIfCreatedDaysAgo() {
        when(buildVersionResolver.currentCommit()).thenReturn("samecommit");
        ImportSession session = sessionOwnedBy(userId, Instant.now().plusSeconds(600), ImportSession.STATUS_STAGED);
        org.springframework.test.util.ReflectionTestUtils.setField(
                session, "createdAt", Instant.now().minus(java.time.Duration.ofHours(40)));
        session.setParserVersion("samecommit");
        when(importSessionRepository.findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                userId, "hash-h", ImportSession.STATUS_STAGED)).thenReturn(Optional.of(session));

        Optional<ImportSession> found = service.findLiveSessionByContentHash(userId, "hash-h");

        assertThat(found).contains(session);
        verify(importSessionRepository, never()).delete(any());
    }

    /** createSession stamps the resolved build version onto every newly-staged session. */
    @Test
    void createSession_stampsTheCurrentParserVersion() {
        when(buildVersionResolver.currentCommit()).thenReturn("stampedcommit");

        ImportSession created = service.createSession(userId, "statement.csv", new byte[]{1, 2, 3},
                List.of(sampleRow()), sampleDetected());

        assertThat(created.getParserVersion()).isEqualTo("stampedcommit");
    }

    /** createMultiSection stamps it too -- a separate code path, not covered by the assertion above. */
    @Test
    void createMultiSection_stampsTheCurrentParserVersion() {
        when(buildVersionResolver.currentCommit()).thenReturn("stampedcommit");
        var section = new com.finora.dto.ImportDto.StagedAccountSection(
                sampleDetected(), List.of(sampleRow()), 1, 0, List.of());

        ImportSession created = service.createMultiSection(userId, "composite.pdf",
                new byte[]{1, 2, 3}, List.of(section));

        assertThat(created.getParserVersion()).isEqualTo("stampedcommit");
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
