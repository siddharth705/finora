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
 * No @Scheduled cleanup job -- this codebase has no background job infrastructure yet (see
 * SystemHealth's own doc comments on why). Expired sessions are opportunistically deleted the
 * next time that same user starts a new import (ImportSessionService.createSession) rather than
 * via a platform-wide sweep; a user who uploads once and never returns leaves one row (with file
 * bytes) sitting until they use import again. Acceptable for a v1, called out explicitly rather
 * than silently left as unbounded growth -- see ADR-0002.
 */
@Entity
@Table(name = "import_sessions")
public class ImportSession implements com.finora.imports.storage.StoredStatement {

    public static final String STATUS_STAGED = "STAGED";
    public static final String STATUS_CONFIRMED = "CONFIRMED";

    public static final String KIND_SINGLE_ACCOUNT = "SINGLE_ACCOUNT";
    public static final String KIND_MULTI_ACCOUNT = "MULTI_ACCOUNT";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /**
     * The staged file's original bytes.
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
    @Column(name = "file_content", nullable = false)
    private byte[] fileContent;

    /** Hex SHA-256 of the staged file -- the document's identity. Null when no storage provider is
     *  configured, in which case the bytes stay in fileContent; see StoredStatement.
     *
     *  A session and the StatementImport it confirms into hold IDENTICAL bytes, so they resolve to
     *  the same address and share one stored object. That is why expiring a session must never
     *  delete its object -- see StatementStorage's note on why there is no delete. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

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

    @Column(name = "session_kind", nullable = false)
    private String sessionKind = KIND_SINGLE_ACCOUNT;

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
    public String getSessionKind() { return sessionKind; }
    public void setSessionKind(String sessionKind) { this.sessionKind = sessionKind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }
}
