package com.finora.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.imports.storage.StatementContentService;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.ImportSessionRepository;
import com.finora.security.OwnershipGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for staged import review state (ADR-0002 / docs/adr/0002-persisted-import-sessions.md).
 * Deliberately a thin persistence + (de)serialization layer, not orchestration -- ImportService
 * still owns the actual stage/confirm business logic, this just gives it somewhere durable to
 * put the in-between state. Kept in com.finora.imports (not com.finora.service) since it's
 * specific to this module, matching PreviewGenerator/DuplicateDetector/etc.
 */
@Component
public class ImportSessionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImportSessionService.class);

    // A user has as long as this to come back and confirm a staged import before it's treated as
    // abandoned. No production usage data to tune this against yet (same "revisit once there's
    // real traffic" caveat as StatementImportHealthProvider's own threshold) -- 48h is picked to
    // comfortably cover "I'll finish this tomorrow," not as a carefully measured number.
    private static final Duration SESSION_TTL = Duration.ofHours(48);

    /** How many expired sessions one import may clean up. Bounds the cost this adds to a user's
     *  own upload; a backlog drains over the next few imports instead of in one long delete. */
    private static final int CLEANUP_BATCH_SIZE = 50;

    @org.springframework.beans.factory.annotation.Value("${app.import.session-cleanup.enabled:true}")
    private boolean sessionCleanupEnabled;

    private final ImportSessionRepository importSessionRepository;
    private final ObjectMapper objectMapper;
    private final StatementContentService statementContentService;

    public ImportSessionService(ImportSessionRepository importSessionRepository, ObjectMapper objectMapper,
                                 StatementContentService statementContentService) {
        this.importSessionRepository = importSessionRepository;
        this.objectMapper = objectMapper;
        this.statementContentService = statementContentService;
    }

    /**
     * Deletes expired sessions -- anyone's -- a bounded batch at a time.
     *
     * <p>Opportunistic rather than a {@code @Scheduled} sweep, because this codebase has no
     * background job infrastructure (see {@code ImportSession}'s own doc comment). What changed is
     * the SCOPE. It used to delete only the acting user's expired rows, which meant an expired
     * session was removed only when that same user started another import -- so a user who
     * imported once and never returned left the row, and the raw statement bytes on it, in the
     * database forever. That is the whole one-time and trial population: by definition they never
     * make the second call that would have cleaned up the first. The stated 48-hour retention was
     * not enforced for exactly the people it applied to, on bank statements, which is the most
     * sensitive data this product holds.
     *
     * <p>The original comment justified user-scoping as avoiding "a full-table scan every time
     * anyone imports anything", and that concern is preserved: the query is bounded to
     * {@link #CLEANUP_BATCH_SIZE} rows and ordered by the indexed expiry, so it is a small
     * index-ordered slice, not a scan. A backlog drains across subsequent imports rather than in
     * one unbounded delete that could stall a user's upload.
     */
    /**
     * Removes a bounded batch of expired sessions, in a transaction of its own.
     *
     * <p>BH-047. This used to run as the first statement of {@code createSession} and
     * {@code createMultiSection}, inside the acting user's transaction, and that coupling was
     * wrong in four separate directions:
     *
     * <ul>
     *   <li>The sweep deletes rows belonging to <b>other users</b>, and {@code ImportSession} has
     *       no {@code @SQLDelete}, so it takes real row locks on them. Those locks were then held
     *       for the rest of the upload -- which includes {@code storeContent}, an object-storage
     *       write. One user's upload latency became a function of another user's network call.</li>
     *   <li>A failure while sweeping somebody else's rows rolled back the acting user's upload.</li>
     *   <li>A failed upload rolled back the sweep, so retention depended on unrelated uploads
     *       succeeding.</li>
     *   <li>Nothing swept at all when nobody was uploading -- and the population the TTL exists
     *       for is precisely the people who imported once and never came back.</li>
     * </ul>
     *
     * <p>The original comment justified the opportunistic placement with "this codebase has no
     * background job infrastructure". That was true when it was written and is not now:
     * {@code BackgroundWorkConfig} enables scheduling unconditionally and two workers already rely
     * on it. The premise expired; the placement outlived it.
     *
     * <p>Still bounded to {@link #CLEANUP_BATCH_SIZE} per run, which preserves the reasoning the
     * original scoping was built on -- a backlog drains across runs rather than in one unbounded
     * delete. At the default interval that is several thousand rows a day, far above any plausible
     * rate of abandoned sessions.
     *
     * @return how many rows were removed, so a caller or a test can see the sweep did something
     */
    @Transactional
    public int sweepExpiredSessions() {
        List<ImportSession> expired = importSessionRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(
                Instant.now(), PageRequest.of(0, CLEANUP_BATCH_SIZE));
        if (expired.isEmpty()) return 0;
        importSessionRepository.deleteAll(expired);
        return expired.size();
    }

    /**
     * The scheduled trigger. Gated by a flag for the same reason the learning queue's poller is:
     * an integration suite needs the sweep to be deterministic, and a background thread deleting
     * rows mid-test is the cross-test pollution BH-058 was about. {@code application-test.yml}
     * turns it off, and tests drive {@link #sweepExpiredSessions()} directly.
     *
     * <p>{@code fixedDelay}, not {@code fixedRate}: the next sweep starts after the previous one
     * finishes, so a slow sweep cannot pile up overlapping runs.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${app.import.session-cleanup.interval-ms:900000}",
            initialDelayString = "${app.import.session-cleanup.initial-delay-ms:60000}")
    public void scheduledSweep() {
        if (!sessionCleanupEnabled) return;
        int removed = sweepExpiredSessions();
        if (removed > 0) {
            log.info("Removed {} expired import session(s) past their {} TTL.", removed, SESSION_TTL);
        }
    }

    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount) {
        return createSession(userId, fileName, fileContent, rows, detectedAccount, null);
    }

    /** Same as {@link #createSession(UUID, String, byte[], List, DetectedAccountInfo)}, plus
     *  persists the {@link DocumentContext}'s structural metadata/fingerprint/capability
     *  activations recorded while staging (Phase 1 "capture facts" -- see
     *  docs/engineering/financial-document-intelligence-principles.md). {@code documentContext}
     *  is nullable -- a caller with none simply leaves those three columns null. */
    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount,
                                        DocumentContext documentContext) {
        return createSession(userId, fileName, fileContent, rows, detectedAccount, documentContext, null);
    }

    /**
     * Same as {@link #createSession(UUID, String, byte[], List, DetectedAccountInfo,
     * DocumentContext)}, plus records where this session came from (C5-B) --
     * {@link ImportSession#SOURCE_GMAIL} or null, per {@link ImportSession#getSource()}'s own doc
     * comment. The only caller with a non-null value is {@code GmailStagingBridge}; every CSV/PDF
     * caller keeps going through one of the two overloads above and this stays null for them,
     * unchanged from before this parameter existed.
     */
    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount,
                                        DocumentContext documentContext, String source) {
        // BH-047: the expired-session sweep used to run here, inside this transaction. It is a
        // scheduled job now -- see sweepExpiredSessions(). Housekeeping on other users' rows has
        // no business being part of this user's upload.
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName(fileName);
        storeContent(session, fileContent);
        session.setStagedRowsJson(writeJson(rows));
        session.setDetectedAccountJson(writeJson(detectedAccount));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        applyDocumentContext(session, documentContext);
        session.setSource(source);
        return importSessionRepository.save(session);
    }

    /** Multi-account equivalent of {@link #createSession} -- for a PDF upload where
     *  {@code PdfPreviewGenerator.generateSections} detected more than one account section (e.g.
     *  HSBC's composite statement). Populates {@code sectionsJson} instead of the two
     *  single-account JSON columns, and marks the session {@link ImportSession#KIND_MULTI_ACCOUNT}
     *  so {@link #readStagedRows}/{@link #readDetectedAccount} refuse to read it by mistake. */
    @Transactional
    public ImportSession createMultiSection(UUID userId, String fileName, byte[] fileContent,
                                             List<StagedAccountSection> sections) {
        return createMultiSection(userId, fileName, fileContent, sections, null);
    }

    /** Same as {@link #createMultiSection(UUID, String, byte[], List)}, plus persists the
     *  {@link DocumentContext} recorded while staging -- one context for the WHOLE document,
     *  shared by every section (they came from the same file). See
     *  {@link #createSession(UUID, String, byte[], List, DetectedAccountInfo, DocumentContext)}'s
     *  own doc comment. */
    @Transactional
    public ImportSession createMultiSection(UUID userId, String fileName, byte[] fileContent,
                                             List<StagedAccountSection> sections, DocumentContext documentContext) {
        // BH-047: the expired-session sweep used to run here, inside this transaction. It is a
        // scheduled job now -- see sweepExpiredSessions(). Housekeeping on other users' rows has
        // no business being part of this user's upload.
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName(fileName);
        storeContent(session, fileContent);
        session.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        session.setSectionsJson(writeJson(sections));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        applyDocumentContext(session, documentContext);
        return importSessionRepository.save(session);
    }

    /**
     * Writes the staged bytes wherever storage is configured to put them, and records the address.
     *
     * Object storage is written FIRST, before the row is persisted -- the ordering the migration
     * doc's §5.1 requires. A failure here throws, so no session row is created; the reverse (a row
     * pointing at an object that was never written) cannot happen.
     *
     * BH-025/BH-046: fileContent is set ONLY when store() came back empty, i.e. no provider
     * configured -- the session stays legacy, exactly as before this fix. When storage IS
     * configured, fileContent is left null; the object is the only copy. This was previously an
     * unconditional dual write, justified as temporary pending a Phase 3 backfill and a Phase 4
     * column drop -- BH-046 found neither survived (Phase 3 was deleted for having nothing to
     * migrate; Phase 4 never got a trigger), so the "temporary" duplication had become permanent.
     * See docs/engineering/statement-storage-migration.md §5.0.
     *
     * <p>contentHash, unlike objectKey, is set in BOTH branches now -- distributed-resilience-
     * patterns-audit-2026-08-14.md §3 / V79 added a second reason a session needs its identity
     * beyond object-storage addressing: {@link #findLiveSessionByContentHash} deduplicates on it.
     * Before this, a deployment with no storage provider configured left every session's
     * contentHash null, which would have made duplicate-upload protection silently inert on
     * exactly the deployment shape this codebase's own tests run under. Computing it directly via
     * {@link com.finora.imports.storage.ContentAddress#hashOf} costs one SHA-256 over bytes
     * already fully in memory -- negligible next to the parse this method's caller just ran.
     */
    private void storeContent(ImportSession session, byte[] fileContent) {
        java.util.Optional<com.finora.imports.storage.ContentAddress> address = statementContentService.store(fileContent);
        if (address.isPresent()) {
            session.setContentHash(address.get().hash());
            session.setObjectKey(address.get().key());
        } else {
            session.setFileContent(fileContent);
            session.setContentHash(com.finora.imports.storage.ContentAddress.hashOf(fileContent));
        }
    }

    private void applyDocumentContext(ImportSession session, DocumentContext documentContext) {
        if (documentContext == null) return;
        session.setLayoutMetadataJson(writeJson(documentContext.buildMetadata()));
        session.setLayoutFingerprint(documentContext.buildFingerprint());
        session.setActivatedCapabilitiesJson(writeJson(documentContext.capabilities()));
        if (documentContext.unparseable() != null && !documentContext.unparseable().isEmpty()) {
            session.setUnparseableSummaryJson(writeJson(documentContext.unparseable()));
        }
    }

    /** Throws (not Optional) -- every real caller needs a valid, owned, still-staged session to
     *  proceed at all; there's no sensible "continue without one" path for either confirm() or
     *  a resume-fetch, so making every caller null-check would just be the same exception
     *  written out three times instead of once here. */
    @Transactional(readOnly = true)
    public ImportSession getOwnedSession(UUID userId, UUID sessionId) {
        ImportSession session = OwnershipGuard.requireOwned(importSessionRepository.findById(sessionId),
                ImportSession::getUserId, userId, "Import session");
        if (session.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This import session has expired -- upload the statement again to continue.");
        }
        if (ImportSession.STATUS_CONFIRMED.equals(session.getStatus())) {
            // Carries ErrorCode.IMPORT_SESSION_ALREADY_CONFIRMED rather than a codeless
            // ApiException like the expiry branch above -- the frontend has to tell these two
            // apart, not just print whatever message arrives. See that code's own comment for the
            // bug this exists to fix (ImportDetail.tsx's "Review this import" reaching a session
            // that was already reviewed and confirmed through the normal flow).
            throw new ApiException(ErrorCode.IMPORT_SESSION_ALREADY_CONFIRMED);
        }
        return session;
    }

    /** Package-private -- {@link #listResumableSessions} is the only caller and the only
     *  public entry point a session list should come through; nothing outside this package
     *  currently needs the unfiltered population. Widen this back to public if a real second
     *  caller (e.g. an admin view) actually shows up. */
    @Transactional(readOnly = true)
    List<ImportSession> listActiveSessions(UUID userId) {
        return importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED)
                .stream()
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .toList();
    }

    /**
     * Same population as {@link #listActiveSessions}, filtered to the kinds the resume UI can
     * actually reopen -- what {@code GET /import/sessions} should return.
     *
     * <p>{@link #listActiveSessions} answers "every session this user could still act on"; it says
     * nothing about whether {@link #readStagedRows}/{@link #readDetectedAccount} can be called on
     * the result. A MULTI_ACCOUNT session is active (staged, unexpired) but {@link #requireKind}
     * rejects it for those two methods, so a caller that maps {@link #listActiveSessions}'s output
     * straight through them throws for any user who has one staged -- their ENTIRE list, not just
     * that session. This method exists so the filtering lives here, next to the kind-awareness
     * {@link #requireKind} already owns, instead of being reconstructed (or silently dropped) by a
     * caller that doesn't know it's required. Named separately from {@link #listActiveSessions}
     * rather than folded into it, since some future caller may genuinely want every active session
     * regardless of kind (e.g. an admin view, or a raw count) -- narrowing that method's contract
     * would take resumability away from callers who never asked for it.
     */
    @Transactional(readOnly = true)
    public List<ImportSession> listResumableSessions(UUID userId) {
        return listActiveSessions(userId).stream()
                .filter(this::supportsResume)
                .toList();
    }

    /** Whether the resume UI can reopen a session of this kind. A {@code switch} rather than a
     *  single equality check so that a THIRD {@link ImportSession} kind can't land on either side
     *  of {@link #listResumableSessions} by accident -- {@code sessionKind} is a plain String, so
     *  the compiler can't enforce exhaustiveness, but an unhandled case here still falls through to
     *  a logged, fail-closed default (excluded, not resumable) rather than either crashing the
     *  whole list the way the original equality-based bug did, or silently including a kind nothing
     *  has actually wired resume support for. Today there are exactly two kinds (confirmed via
     *  {@code grep KIND_ ImportSession.java}) and only SINGLE_ACCOUNT is resumable: that's what
     *  {@link #readStagedRows}/{@link #readDetectedAccount} -- which the resume flow reads through
     *  {@code ImportController.toSummary} -- both {@link #requireKind}. */
    private boolean supportsResume(ImportSession session) {
        return switch (session.getSessionKind()) {
            case ImportSession.KIND_SINGLE_ACCOUNT -> true;
            case ImportSession.KIND_MULTI_ACCOUNT -> false;
            default -> {
                log.warn("Import session {} has unrecognized kind '{}' -- excluding it from the resumable list.",
                        session.getId(), session.getSessionKind());
                yield false;
            }
        };
    }

    /**
     * This user's own live (STAGED, unexpired) session for this exact document, if one exists --
     * the app-level half of V79's duplicate-upload protection for the synchronous stage path
     * (POST /csv/stage, /pdf/stage). {@code ImportService} calls this BEFORE parsing, not after,
     * because the expensive part of a double-clicked upload or a retried request is the parse
     * itself; a check that only ran at session-creation time would still pay for the second parse
     * even though it correctly stopped a second row from being written.
     *
     * <p>Not the correctness guarantee -- this is a read followed by a possible write, so two
     * genuinely simultaneous uploads of the same file can both see no match and both proceed to
     * parse. {@code idx_import_sessions_live_content} (V79) is what actually decides then: the
     * loser's {@code createSession}/{@code createMultiSection} INSERT hits the constraint and
     * {@code GlobalExceptionHandler} answers a {@code DataIntegrityViolationException} as 409, the
     * same shape V74 already established for {@code import_jobs}.
     *
     * <p>An expired match is deleted here rather than returned as a block. The unique index this
     * method serves cannot express "and not expired" -- a partial index predicate must be
     * immutable, so it cannot reference {@code now()} -- which means a STAGED session that expired
     * but has not yet been swept ({@link #sweepExpiredSessions} runs on a schedule, not instantly)
     * would otherwise make a genuinely new upload of the same statement fail with a false
     * duplicate. Deleting it here is a strict subset of what the scheduled sweep already does to
     * the same row; this just does it eagerly, on the one request that actually needs the row
     * gone right now.
     */
    @Transactional
    public Optional<ImportSession> findLiveSessionByContentHash(UUID userId, String contentHash) {
        Optional<ImportSession> match = importSessionRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                        userId, contentHash, ImportSession.STATUS_STAGED);
        if (match.isEmpty()) return Optional.empty();

        ImportSession session = match.get();
        if (session.getExpiresAt().isAfter(Instant.now())) {
            return Optional.of(session);
        }
        importSessionRepository.delete(session);
        return Optional.empty();
    }

    /**
     * Atomically claims a session for confirmation -- the actual fix for the double-submission
     * race a double-click or retried request could trigger (see
     * ImportSessionRepository.claimForConfirmation's own doc comment for why a plain
     * read-then-save wouldn't have been enough). getOwnedSession() first for the ownership/
     * expiry checks (a genuinely unauthorized or expired attempt should fail with a clear reason,
     * not a generic "already confirmed"); the atomic UPDATE afterward is what actually prevents
     * two concurrent legitimate requests from both proceeding. Called as the very first thing
     * confirmSession() does, before any transaction-import work happens at all -- if this method
     * throws, nothing downstream has touched the transactions table yet.
     */
    @Transactional
    public ImportSession claimForConfirmation(UUID userId, UUID sessionId) {
        getOwnedSession(userId, sessionId); // ownership + expiry + not-already-confirmed, for a clear error message
        int updated = importSessionRepository.claimForConfirmation(sessionId);
        if (updated == 0) {
            // Lost the race between the read above and this atomic update -- someone/something
            // else (a concurrent duplicate request for the same session) claimed it first.
            throw new ApiException(HttpStatus.CONFLICT, "This import has already been confirmed.");
        }
        return importSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Import session not found."));
    }

    @Transactional
    public void deleteSession(UUID userId, UUID sessionId) {
        ImportSession session = getOwnedSession(userId, sessionId);
        importSessionRepository.delete(session);
    }

    public List<StagedRow> readStagedRows(ImportSession session) {
        requireKind(session, ImportSession.KIND_SINGLE_ACCOUNT);
        return readJson(session.getStagedRowsJson(), new TypeReference<List<StagedRow>>() {});
    }

    public DetectedAccountInfo readDetectedAccount(ImportSession session) {
        requireKind(session, ImportSession.KIND_SINGLE_ACCOUNT);
        return readJson(session.getDetectedAccountJson(), DetectedAccountInfo.class);
    }

    /** Multi-account equivalent of {@link #readStagedRows}/{@link #readDetectedAccount} -- see
     *  {@link #createMultiSection}. */
    public List<StagedAccountSection> readSections(ImportSession session) {
        requireKind(session, ImportSession.KIND_MULTI_ACCOUNT);
        return readJson(session.getSectionsJson(), new TypeReference<List<StagedAccountSection>>() {});
    }

    /** Guards against reading the wrong pair of JSON columns for a session's actual kind --
     *  e.g. a client calling the single-account confirm endpoint against a session that was
     *  actually staged as MULTI_ACCOUNT (sectionsJson populated, the other two columns null). */
    private void requireKind(ImportSession session, String expectedKind) {
        if (!expectedKind.equals(session.getSessionKind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This import session is a " + session.getSessionKind()
                            + " session -- use the matching stage/confirm endpoint for it.");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // StagedRow/DetectedAccountInfo are plain records of primitives/BigDecimal/LocalDate
            // -- Jackson serializing them failing at all would mean something is structurally
            // broken (a non-serializable type snuck in), not a normal runtime condition to
            // recover from gracefully.
            throw new IllegalStateException("Failed to serialize import session state", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize import session state", e);
        }
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize import session state", e);
        }
    }
}
