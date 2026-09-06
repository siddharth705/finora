package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Persisted staged-review state (ADR-0002) -- the gap this closes: previously, everything
 * between /import/csv/stage and /import/csv/confirm lived only in the frontend's memory and the
 * HTTP round-trip; a dropped browser session lost all review progress and required re-uploading
 * the file from scratch. Now the staged rows, detected account info, and the original file bytes
 * are persisted here at staging time, so a resumed session (same account, different device, or
 * just a reloaded tab) can pick back up without re-uploading anything.
 *
 * stagedRowsJson/detectedAccountJson are Jackson-serialized ImportDto.StagedRow list / DetectedAccountInfo
 * -- stored as JSON text rather than new normalized tables, matching this row's actual lifecycle:
 * it's a transient staging artifact (deleted or marked CONFIRMED once the real transactions/
 * StatementImport rows exist), not permanent financial data that needs to be queried in its own
 * right the way transactions do.
 *
 * Bug fix: this comment used to say there was no {@code @Scheduled} cleanup job, and that expired
 * sessions were only opportunistically deleted the next time that same user started a new import
 * -- true when it was written, and wrong now. BH-047 replaced that with a real scheduled sweep
 * ({@code ImportSessionService.scheduledSweep}, every 15 minutes by default) that hard-deletes any
 * user's rows once past their 48-hour TTL, in bounded batches -- see that method's own doc comment
 * for why the opportunistic, per-user-scoped placement was wrong. A user who uploads once and
 * never returns is exactly who this sweep is for: no second visit is needed to clean up the first.
 */
@Entity
@Table(name = "import_sessions")
public class ImportSession implements com.finora.imports.storage.StoredStatement {

    public static final String STATUS_STAGED = "STAGED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    public static final String KIND_SINGLE_ACCOUNT = "SINGLE_ACCOUNT";
    public static final String KIND_MULTI_ACCOUNT = "MULTI_ACCOUNT";

    /** The only non-null value today (C5-B). Null means CSV or PDF -- both currently confirm into
     *  {@code Transaction.Source.CSV_IMPORT}, unchanged from before this field existed. */
    public static final String SOURCE_GMAIL = "GMAIL";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * The staged file's original bytes -- ALWAYS populated. Staging deliberately keeps a file in
     * temporary (database) storage only; nothing is written to object storage until the user
     * confirms the import (see {@code ImportSessionService.storeContent} and
     * {@code ImportService.persistSection}'s own doc comments). A session therefore never reaches
     * the "addressed" state {@link com.finora.imports.storage.StoredStatement}'s class doc
     * describes for a confirmed {@code StatementImport} -- {@link #objectKey} stays null for the
     * whole life of every session.
     *
     * <p><b>LAZY, matching {@code StatementImport.fileContent}.</b> Without this, JPA's default for
     * a basic {@code byte[]} applies — EAGER — so every query that touched an {@code ImportSession}
     * dragged the whole upload with it, bounded only by the 10 MB multipart cap. The worst case was
     * {@code ImportSessionService.cleanupExpired}, which loads a batch of expired sessions purely
     * to delete them and was therefore materialising other users' complete statement files into the
     * heap of whichever user happened to trigger the sweep.
     *
     * <p>Every read of these bytes goes through {@code StatementContentService.read} from inside a
     * transaction ({@code ImportService.confirmSession} / {@code confirmMultiSection}), which is
     * what makes lazy safe here — the same precondition {@code StatementImport} already relies on.
     */
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_content")
    private byte[] fileContent;

    /** Hex SHA-256 of the staged file -- the document's identity, always computed regardless of
     *  whether object storage is configured (see ImportSessionService.storeContent). Nullable only
     *  for rows staged before V79 added idx_import_sessions_live_content; every row created since
     *  carries one -- it is what that partial unique index and
     *  ImportSessionService.findLiveSessionByContentHash deduplicate the synchronous stage path
     *  (POST /csv/stage, /pdf/stage) on. A session's content_hash and the StatementImport it later
     *  confirms into carry the SAME value (both hash the same original bytes), which is what lets a
     *  duplicate-upload check compare across the two tables even though only the confirmed row ever
     *  gets an object_key. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** Always null. Staging never writes to object storage -- see {@link #fileContent}'s own doc
     *  comment. The column exists only because {@link com.finora.imports.storage.StoredStatement}
     *  is one interface for two entities, and dropping it here would need its own migration for no
     *  behavioural gain: nothing currently reads a session's object_key, and nothing should start
     *  to. */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    // Nullable as of V37: populated for a SINGLE_ACCOUNT session, left null for a MULTI_ACCOUNT
    // one (which uses sectionsJson instead) -- see ImportSessionService's read-side guard, which
    // throws a clear error rather than letting a caller silently read null/garbage from the wrong
    // pair of columns for a given session's actual kind.
    @Column(name = "staged_rows_json", columnDefinition = "TEXT")
    private String stagedRowsJson;

    @Column(name = "detected_account_json", columnDefinition = "TEXT")
    private String detectedAccountJson;

    // Jackson-serialized List<ImportDto.StagedAccountSection> -- populated only for a
    // MULTI_ACCOUNT session (e.g. an HSBC composite-statement upload detecting more than one
    // account section), left null for a SINGLE_ACCOUNT one.
    @Column(name = "sections_json", columnDefinition = "TEXT")
    private String sectionsJson;

    // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // one DocumentContext's worth of recorded structural facts + deterministic layout ID +
    // activated-capability events for the WHOLE uploaded document (all sections of a multi-account
    // PDF share one set -- they came from the same file). Nullable: a session created before this
    // column existed, or a session whose staging path didn't build a DocumentContext, simply has
    // none -- never guessed or backfilled.
    @Column(name = "layout_metadata_json", columnDefinition = "TEXT")
    private String layoutMetadataJson;

    @Column(name = "layout_fingerprint", length = 20)
    private String layoutFingerprint;

    @Column(name = "unparseable_summary_json", columnDefinition = "TEXT")
    private String unparseableSummaryJson;

    @Column(name = "activated_capabilities_json", columnDefinition = "TEXT")
    private String activatedCapabilitiesJson;

    /** Credit-card statement entity, roadmap item 6 follow-up (PR #451's totalAmountDue/
     *  paymentDueDate already survived staging via {@link #detectedAccountJson}/{@link
     *  #sectionsJson}; this carries {@code CreditCardSummaryExtractor.CreditCardSummaryEvidence}'s
     *  full balance breakdown, which is not on {@code DetectedAccountInfo} and would otherwise be
     *  discarded after staging). Jackson-serialized, PDF-only, null whenever no such panel was
     *  found -- same "best-effort, never recomputed after the fact" discipline as {@link
     *  #layoutMetadataJson} and its siblings. */
    @Column(name = "credit_card_summary_json", columnDefinition = "TEXT")
    private String creditCardSummaryJson;

    @Column(name = "session_kind", nullable = false)
    private String sessionKind = KIND_SINGLE_ACCOUNT;

    /** Null for CSV/PDF (unchanged behaviour), {@link #SOURCE_GMAIL} for a session
     *  {@code GmailStagingBridge} created. See V84's migration comment for why this is a column
     *  rather than something inferred at confirm time. */
    @Column(name = "source", length = 20)
    private String source;

    /** Null for CSV/PDF and for a Gmail session predating this column (unchanged, imperfect
     *  fallback behaviour); the authenticated domain a Gmail receipt actually came from, set only
     *  by {@code GmailStagingBridge}. See V108's migration comment for why this exists separately
     *  from the staged row's own description, which can now be a counterparty name instead of the
     *  domain -- {@code GmailReviewService} needs the real domain for its review-queue reasoning
     *  regardless of what the description shows. */
    @Column(name = "source_domain", length = 253)
    private String sourceDomain;

    /** The short commit id ({@code BuildVersionResolver.currentCommit()}) of the backend build
     *  that staged this session -- null for any session staged before this column existed. Read
     *  by {@code ImportSessionService.findLiveSessionByContentHash} to decide whether a session is
     *  safe to replay automatically: a mismatch against the CURRENT build's commit means the
     *  parser may have changed since this session was staged, regardless of how little time has
     *  passed. */
    @Column(name = "parser_version", length = 40)
    private String parserVersion;

    @Column(nullable = false)
    private String status = STATUS_STAGED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    @Override public byte[] getFileContent() { return fileContent; }
    public void setFileContent(byte[] fileContent) { this.fileContent = fileContent; }
    @Override public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    @Override public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    // Always NONE: a session never has an objectKey to decode, and never persists a
    // compression_type column of its own -- see fileContent's and objectKey's own doc comments.
    @Override public com.finora.imports.storage.CompressionType getCompressionType() {
        return com.finora.imports.storage.CompressionType.NONE;
    }
    // Always null, same reasoning: a session's objectKey is always null (see its own doc comment),
    // so there is never anything to decrypt and no encryption_key_id column of its own to persist.
    @Override public String getEncryptionKeyId() {
        return null;
    }
    public String getStagedRowsJson() { return stagedRowsJson; }
    public void setStagedRowsJson(String stagedRowsJson) { this.stagedRowsJson = stagedRowsJson; }
    public String getDetectedAccountJson() { return detectedAccountJson; }
    public void setDetectedAccountJson(String detectedAccountJson) { this.detectedAccountJson = detectedAccountJson; }
    public String getSectionsJson() { return sectionsJson; }
    public void setSectionsJson(String sectionsJson) { this.sectionsJson = sectionsJson; }
    public String getLayoutMetadataJson() { return layoutMetadataJson; }
    public void setLayoutMetadataJson(String layoutMetadataJson) { this.layoutMetadataJson = layoutMetadataJson; }
    public String getUnparseableSummaryJson() { return unparseableSummaryJson; }
    public void setUnparseableSummaryJson(String unparseableSummaryJson) { this.unparseableSummaryJson = unparseableSummaryJson; }
    public String getLayoutFingerprint() { return layoutFingerprint; }
    public void setLayoutFingerprint(String layoutFingerprint) { this.layoutFingerprint = layoutFingerprint; }
    public String getActivatedCapabilitiesJson() { return activatedCapabilitiesJson; }
    public void setActivatedCapabilitiesJson(String activatedCapabilitiesJson) { this.activatedCapabilitiesJson = activatedCapabilitiesJson; }
    public String getCreditCardSummaryJson() { return creditCardSummaryJson; }
    public void setCreditCardSummaryJson(String creditCardSummaryJson) { this.creditCardSummaryJson = creditCardSummaryJson; }
    public String getSessionKind() { return sessionKind; }
    public void setSessionKind(String sessionKind) { this.sessionKind = sessionKind; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getSourceDomain() { return sourceDomain; }
    public void setSourceDomain(String sourceDomain) { this.sourceDomain = sourceDomain; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
