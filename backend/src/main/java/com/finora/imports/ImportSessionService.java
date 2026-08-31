package com.finora.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.config.BuildVersionResolver;
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

    // Phase 1B superseded this as the PRIMARY freshness signal -- see findLiveSessionByContentHash
    // -- but kept it as the fallback for the one case a build-version comparison can't resolve: an
    // environment with no git metadata and no RAILWAY_GIT_COMMIT_SHA/GIT_COMMIT set (local dev
    // without either, and this class's own test suite). In that case there's no way to tell
    // "the parser might have changed" from "nothing changed, this is a real retry", so this window
    // is what still protects a double-click or a retried request from becoming two parses, exactly
    // as it did before version comparison existed. Deliberately much shorter than SESSION_TTL,
    // which answers a different question ("how long can a user wait before resuming a review they
    // started", correctly measured in days) than this one ("how long ago must this exact upload
    // have happened for it to plausibly be the same attempt", correctly measured in minutes).
    private static final Duration DUPLICATE_UPLOAD_DEDUP_WINDOW = Duration.ofMinutes(5);

    /** How many expired sessions one import may clean up. Bounds the cost this adds to a user's
     *  own upload; a backlog drains over the next few imports instead of in one long delete. */
    private static final int CLEANUP_BATCH_SIZE = 50;

    @org.springframework.beans.factory.annotation.Value("${app.import.session-cleanup.enabled:true}")
    private boolean sessionCleanupEnabled;

    private final ImportSessionRepository importSessionRepository;
    private final ObjectMapper objectMapper;
    private final BuildVersionResolver buildVersionResolver;

    public ImportSessionService(ImportSessionRepository importSessionRepository, ObjectMapper objectMapper,
                                 BuildVersionResolver buildVersionResolver) {
        this.importSessionRepository = importSessionRepository;
        this.objectMapper = objectMapper;
        this.buildVersionResolver = buildVersionResolver;
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
        return createSession(userId, fileName, fileContent, rows, detectedAccount, documentContext, null, null);
    }

    /**
     * Same as {@link #createSession(UUID, String, byte[], List, DetectedAccountInfo,
     * DocumentContext)}, plus records where this session came from (C5-B) --
     * {@link ImportSession#SOURCE_GMAIL} or null, per {@link ImportSession#getSource()}'s own doc
     * comment -- and, independently, the authenticated domain a Gmail receipt actually came from
     * (C5 follow-up, see V108's migration comment for why this is separate from the staged row's
     * own description). The only caller with non-null values for either is {@code
     * GmailStagingBridge}; every CSV/PDF caller keeps going through one of the two overloads above
     * and both stay null for them, unchanged from before these parameters existed.
     */
    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount,
                                        DocumentContext documentContext, String source,
                                        String sourceDomain) {
        return createSession(userId, fileName, fileContent, rows, detectedAccount, documentContext,
                source, sourceDomain, null);
    }

    /**
     * Same as {@link #createSession(UUID, String, byte[], List, DetectedAccountInfo,
     * DocumentContext)}, plus the credit-card statement entity's balance breakdown (roadmap item 6
     * follow-up, PR #451) -- {@code CreditCardSummaryEvidence}, the same document-level reading
     * {@code PdfPreviewGenerator} already computed and attached to every section's {@code
     * DetectedAccountInfo.totalAmountDue}, carried here in full so the rest of it (previousBalance/
     * purchases/cashAdvances/fees/paymentsAndCredits) survives to confirm too. The only caller is
     * the PDF single-section staging path -- CSV has no such panel to read, and Gmail's own
     * overload (source/sourceDomain, above) never carries one either.
     */
    @Transactional
    public ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount,
                                        DocumentContext documentContext,
                                        com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence creditCardSummary) {
        return createSession(userId, fileName, fileContent, rows, detectedAccount, documentContext,
                null, null, creditCardSummary);
    }

    private ImportSession createSession(UUID userId, String fileName, byte[] fileContent,
                                        List<StagedRow> rows, DetectedAccountInfo detectedAccount,
                                        DocumentContext documentContext, String source,
                                        String sourceDomain,
                                        com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence creditCardSummary) {
        // BH-047: the expired-session sweep used to run here, inside this transaction. It is a
        // scheduled job now -- see sweepExpiredSessions(). Housekeeping on other users' rows has
        // no business being part of this user's upload.
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName(fileName);
        // Temporary storage only -- this file does NOT go to R2 here. It stays in
        // import_sessions.file_content until the user presses Import; see storeContent()'s own
        // doc comment for why, and ImportService.persistSection for where R2 is actually reached.
        storeContent(session, fileContent);
        session.setStagedRowsJson(writeJson(rows));
        session.setDetectedAccountJson(writeJson(detectedAccount));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session.setParserVersion(buildVersionResolver.currentCommit());
        applyDocumentContext(session, documentContext);
        session.setSource(source);
        session.setSourceDomain(sourceDomain);
        // NONE (never null, see that constant's own doc comment) is written as null rather than as
        // an all-fields-null JSON blob -- persistSection's own read side treats "no panel found"
        // and "never computed at all" identically, so there's no reason to distinguish them here.
        if (creditCardSummary != null
                && creditCardSummary != com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE) {
            session.setCreditCardSummaryJson(writeJson(creditCardSummary));
        }
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
        return createMultiSection(userId, fileName, fileContent, sections, documentContext, null);
    }

    /** Same as {@link #createMultiSection(UUID, String, byte[], List, DocumentContext)}, plus the
     *  credit-card statement entity's balance breakdown -- see {@link #createSession(UUID, String,
     *  byte[], List, DetectedAccountInfo, DocumentContext,
     *  com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence)}'s own doc
     *  comment. Document-level, same as {@code documentContext} -- a genuinely multi-account PDF
     *  with a credit-card section still has only one billing-summary panel to read. */
    @Transactional
    public ImportSession createMultiSection(UUID userId, String fileName, byte[] fileContent,
                                             List<StagedAccountSection> sections, DocumentContext documentContext,
                                             com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence creditCardSummary) {
        // BH-047: the expired-session sweep used to run here, inside this transaction. It is a
        // scheduled job now -- see sweepExpiredSessions(). Housekeeping on other users' rows has
        // no business being part of this user's upload.
        ImportSession session = new ImportSession();
        session.setUserId(userId);
        session.setFileName(fileName);
        // Temporary storage only -- see the single-section createSession's identical comment above.
        storeContent(session, fileContent);
        session.setSessionKind(ImportSession.KIND_MULTI_ACCOUNT);
        session.setSectionsJson(writeJson(sections));
        session.setExpiresAt(Instant.now().plus(SESSION_TTL));
        session.setParserVersion(buildVersionResolver.currentCommit());
        applyDocumentContext(session, documentContext);
        if (creditCardSummary != null
                && creditCardSummary != com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE) {
            session.setCreditCardSummaryJson(writeJson(creditCardSummary));
        }
        return importSessionRepository.save(session);
    }

    /**
     * Writes the staged bytes to temporary (database) storage. ALWAYS -- staging never writes to
     * object storage, regardless of whether a provider is configured.
     *
     * <p><b>Storage review, lifecycle change.</b> This used to write through to object storage
     * (via {@code StatementContentService.store}) at STAGING time whenever a provider was
     * configured, on the reasoning that R2 was durable and the database copy was not (BH-025/
     * BH-046). That put bytes in R2 for statements a user had merely uploaded and had not yet
     * reviewed or confirmed -- most staged sessions ARE reviewed and confirmed, but the ones that
     * aren't (an abandoned upload, a session left to expire) had already paid the R2 write for
     * nothing. The product requirement is now explicit: a file stays in temporary storage only
     * until the user presses Import. {@code import_sessions.file_content} already IS that temporary
     * store -- self-cleaning via the existing 48h TTL sweep ({@link #sweepExpiredSessions}), scoped
     * to the owning user, needing no new component. Object storage is now reached for the first
     * time at CONFIRM, by {@code ImportService.persistSection} -- see that method's own doc comment
     * for the compression this now also applies at that point.
     *
     * <p>{@code contentHash} is still computed here, unconditionally -- it has nothing to do with
     * object storage. {@link #findLiveSessionByContentHash} deduplicates the staging path on it
     * (V79 / distributed-resilience-patterns-audit-2026-08-14.md §3), and it is the SAME value the
     * confirmed {@code StatementImport} row will carry, since both hash the same original bytes.
     * Computing it directly via {@link com.finora.imports.storage.ContentAddress#hashOf} costs one
     * SHA-256 over bytes already fully in memory -- negligible next to the parse this method's
     * caller just ran.
     */
    private void storeContent(ImportSession session, byte[] fileContent) {
        session.setFileContent(fileContent);
        session.setContentHash(com.finora.imports.storage.ContentAddress.hashOf(fileContent));
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
     *
     * <p><b>Phase 1B: build-version comparison is the real freshness signal now, not age.</b> A
     * real incident (2026-08-31, a real HDFC statement): a session staged under an old, buggy
     * parser kept getting replayed on every later re-upload, because "still within its 48h TTL"
     * was being used as a proxy for "this is the same upload attempt", which it never was -- the
     * TTL answers "can the user still resume this review", not "was this staged by the build
     * that's running now". Phase 1A's fix (bounding replay to a short {@link
     * #DUPLICATE_UPLOAD_DEDUP_WINDOW}) shrank that gap but didn't close it: a session created
     * seconds before a fix deploys could still be replayed for the rest of that window. This
     * closes it properly by asking the actual question directly -- was this session staged by the
     * exact build that's deciding whether to replay it -- whenever that question is answerable at
     * all ({@link BuildVersionResolver#currentCommit()} non-null). A mismatch, including a
     * pre-this-feature session whose {@code parserVersion} is null, forces a fresh parse
     * regardless of age; a match replays regardless of age (still bounded by the TTL check above).
     * The dedup window is kept as the fallback for the one case version comparison can't resolve:
     * no git metadata and no {@code RAILWAY_GIT_COMMIT_SHA}/{@code GIT_COMMIT} configured (local
     * dev without either, and this class's own test suite) -- there, "the parser might have
     * changed" can't be told apart from "nothing changed, this is a real retry", so this falls
     * back to the same short-window heuristic Phase 1A introduced rather than either always
     * trusting (the original bug) or never trusting (breaking double-click/retry protection in
     * every such environment).
     */
    @Transactional
    public Optional<ImportSession> findLiveSessionByContentHash(UUID userId, String contentHash) {
        Optional<ImportSession> match = importSessionRepository
                .findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
                        userId, contentHash, ImportSession.STATUS_STAGED);
        if (match.isEmpty()) return Optional.empty();

        ImportSession session = match.get();
        boolean expired = session.getExpiresAt().isBefore(Instant.now());

        String currentVersion = buildVersionResolver.currentCommit();
        boolean stale;
        if (currentVersion != null) {
            // A null stored version (a session staged before this column existed) can never equal
            // a real resolved commit, so it's correctly treated as a mismatch here -- not a
            // coincidental match of two unset values.
            stale = !currentVersion.equals(session.getParserVersion());
        } else {
            stale = session.getCreatedAt().isBefore(Instant.now().minus(DUPLICATE_UPLOAD_DEDUP_WINDOW));
        }

        if (!expired && !stale) {
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

    /** Credit-card statement entity, roadmap item 6 follow-up (PR #451) -- {@link
     *  ImportSession#getCreditCardSummaryJson()} deserialized back, or {@code
     *  CreditCardSummaryEvidence.NONE} when the column is null (no summary panel was found, or this
     *  session predates the column). No kind guard, unlike the three methods above: a credit-card
     *  panel is document-level and applies equally to a SINGLE_ACCOUNT or MULTI_ACCOUNT session --
     *  see {@code createMultiSection}'s own doc comment. */
    public com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence readCreditCardSummary(ImportSession session) {
        String json = session.getCreditCardSummaryJson();
        if (json == null) return com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.NONE;
        return readJson(json, com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence.class);
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
