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
import com.finora.repository.ImportSessionRepository;
import com.finora.security.OwnershipGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
     * fileContent is still set regardless. Phase 2 is a dual write, not a move: until Phase 3 has
     * backfilled and Phase 4 has dropped the column, the database copy is what makes an
     * object-storage problem recoverable rather than terminal.
     */
    private void storeContent(ImportSession session, byte[] fileContent) {
        statementContentService.store(fileContent).ifPresent(address -> {
            session.setContentHash(address.hash());
            session.setObjectKey(address.key());
        });
        session.setFileContent(fileContent);
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
            throw new ApiException(HttpStatus.BAD_REQUEST, "This import has already been confirmed.");
        }
        return session;
    }

    @Transactional(readOnly = true)
    public List<ImportSession> listActiveSessions(UUID userId) {
        return importSessionRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, ImportSession.STATUS_STAGED)
                .stream()
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .toList();
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
