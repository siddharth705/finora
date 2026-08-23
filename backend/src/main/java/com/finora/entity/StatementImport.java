package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "statement_imports")
// Same version-safe soft-delete pattern as Account/Budget/Goal/Transaction (see those entities'
// comments): StatementImport extends BaseEntity, so it has @Version, and Hibernate binds a
// second (version) parameter on any delete of a versioned entity regardless of custom @SQLDelete
// SQL. Omitting "AND version = ?" here would reintroduce the exact "No value specified for
// parameter 2" bug that was already found and fixed on the other four entities.
@SQLDelete(sql = "UPDATE statement_imports SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class StatementImport extends BaseEntity implements com.finora.imports.storage.StoredStatement {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    /** "CSV" or "PDF" -- explicit, not inferred from fileName's extension at reimport time (see
     *  StatementImportService.reimport()'s own comment for why that was a real, if narrow,
     *  fragility this replaces). Set once, at confirm() time, from whichever staging path
     *  actually produced this row. */
    @Column(name = "source_format", nullable = false)
    private String sourceFormat = "CSV";

    /** Which section (0-based) of a multi-account PDF this row came from -- null for every
     *  CSV/single-account-PDF import. Required for reimport() to replay the correct section of a
     *  composite statement (e.g. HSBC) instead of always re-parsing section 0 -- see
     *  ImportService.parseAndStageAnyFormat()'s section-aware branch. */
    @Column(name = "source_section_index")
    private Integer sourceSectionIndex;

    // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // copied verbatim from the ImportSession this confirm came from -- see
    // ImportService.confirm()'s trailing parameters. Null whenever this row was confirmed through
    // a path with no session available (e.g. StatementImportService.confirmReimport(), which
    // replays already-stored bytes rather than a fresh staged session) -- best-effort, same as
    // every other nullable field on this pipeline, never recomputed after the fact.
    @Column(name = "layout_metadata_json", columnDefinition = "TEXT")
    private String layoutMetadataJson;

    @Column(name = "layout_fingerprint", length = 20)
    private String layoutFingerprint;

    @Column(name = "activated_capabilities_json", columnDefinition = "TEXT")
    private String activatedCapabilitiesJson;

    // Phase 4 step 12: what failed to parse in this import, counted by reason and column shape --
    // never the rows themselves, which are lines of the customer's statement. See
    // UnparseableRowSummary for why a histogram rather than the values.
    @Column(name = "unparseable_summary_json", columnDefinition = "TEXT")
    private String unparseableSummaryJson;

    // Raw CSV bytes — kept so "Re-import Statement" can replay the exact file and "Download
    // Original File" has something to serve. Lazy-fetched: every list/history view only needs
    // the metadata columns, not the file bytes, so this shouldn't ride along on those queries.
    // Deliberately NOT @Lob: on PostgreSQL, Hibernate maps @Lob byte[] to the `oid` large-object
    // type, but the V10 migration created a plain `bytea` column (simpler — no separate large
    // object storage/cleanup to manage). JdbcTypeCode(VARBINARY) is what actually matches `bytea`.
    //
    // Nullable as of V76 (BH-025/BH-046): null exactly when contentHash/objectKey are set --
    // ImportService.persistSection writes bytes here only when statementContentService.store()
    // came back empty (no provider configured). A row with an object address has its bytes in
    // object storage only; StatementContentService.read is the one place that resolves either
    // case back to bytes, mirroring StoredStatement's contract.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_content")
    private byte[] fileContent;

    @Column(name = "statement_period_start")
    private LocalDate statementPeriodStart;

    @Column(name = "statement_period_end")
    private LocalDate statementPeriodEnd;

    @Column(name = "opening_balance")
    private BigDecimal openingBalance;

    @Column(name = "closing_balance")
    private BigDecimal closingBalance;

    @Column(name = "transactions_imported", nullable = false)
    private int transactionsImported;

    @Column(name = "transactions_skipped", nullable = false)
    private int transactionsSkipped;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt = Instant.now();

    /** Wall-clock ms for the confirm that created this row; null for imports predating V53. The
     *  number was already being computed and returned on ConfirmResponse -- V53 just stops throwing
     *  it away, so LayoutIntelligenceService can answer whether recurring layouts import faster. */
    @Column(name = "import_duration_ms")
    private Long importDurationMs;

    /** Hex SHA-256 of the original file -- the document's identity. Null for rows predating V54
     *  (Phase 2), which still read from fileContent; see StoredStatement. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    /** Where the configured provider put {@link #contentHash}. A layout detail, deliberately
     *  separate from identity -- see ContentAddress. */
    @Column(name = "object_key", length = 512)
    private String objectKey;

    /** Byte count of the original, uncompressed upload -- null for a legacy row (no compression
     *  metadata was ever recorded for it; see V92's migration comment). Independent of
     *  {@link #contentHash}'s meaning: this is a size measurement, not part of the document's
     *  identity. */
    @Column(name = "original_size")
    private Long originalSize;

    /** Byte count actually written to the object store -- the compressed size when
     *  {@link #compressionType} is {@code GZIP}, otherwise equal to {@link #originalSize}. Null for
     *  a legacy row, same as {@link #originalSize}. Kept purely for storage-savings measurement --
     *  see V92's migration comment for why this schema exists (Cloudflare R2 storage review). */
    @Column(name = "stored_size")
    private Long storedSize;

    /** How the bytes at {@link #objectKey} are encoded -- see {@link
     *  com.finora.imports.storage.CompressionType}'s own doc for why this is explicit metadata
     *  rather than sniffed from the bytes on read. Defaults to {@code NONE}: correct for every row
     *  predating this column (V92) and for the no-storage-provider fallback, where {@link
     *  #fileContent} holds the bytes exactly as uploaded. */
    @Enumerated(EnumType.STRING)
    @Column(name = "compression_type", nullable = false, length = 10)
    private com.finora.imports.storage.CompressionType compressionType = com.finora.imports.storage.CompressionType.NONE;

    /** The upload's MIME type ({@code application/pdf}, {@code text/csv}) -- recorded once, at
     *  confirm time, from {@link #sourceFormat}. Metadata only; nothing on the read path branches
     *  on it -- {@link #compressionType} alone decides how to decode the bytes. */
    @Column(name = "original_mime_type", length = 100)
    private String originalMimeType;

    /** The key {@link com.finora.security.crypto.EncryptionService} encrypted {@link #objectKey}'s
     *  bytes under (V107) -- null for a legacy row or one written before encryption shipped, in
     *  which case {@link com.finora.imports.storage.StatementContentService#read} does not attempt
     *  to decrypt. See {@link com.finora.imports.storage.StoredStatement#getEncryptionKeyId()}. */
    @Column(name = "encryption_key_id", length = 50)
    private String encryptionKeyId;

    /**
     * The async job that produced this import, or null for the synchronous path.
     *
     * <p>UNIQUE in the database (V67). That constraint is what makes a replayed job safe: a worker
     * that died after confirming and before completing gets its job returned to the queue, and the
     * second attempt's insert is rejected rather than importing the same statement twice.
     *
     * <p>Enforced by the database rather than by a check in the worker, because a check is a read
     * followed by a write and two workers can both read "not present" before either writes.
     */
    @Column(name = "import_job_id")
    private UUID importJobId;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getSourceFormat() { return sourceFormat; }
    public void setSourceFormat(String sourceFormat) { this.sourceFormat = sourceFormat; }
    public Integer getSourceSectionIndex() { return sourceSectionIndex; }
    public void setSourceSectionIndex(Integer sourceSectionIndex) { this.sourceSectionIndex = sourceSectionIndex; }
    public String getLayoutMetadataJson() { return layoutMetadataJson; }
    public void setLayoutMetadataJson(String layoutMetadataJson) { this.layoutMetadataJson = layoutMetadataJson; }
    public String getLayoutFingerprint() { return layoutFingerprint; }
    public void setLayoutFingerprint(String layoutFingerprint) { this.layoutFingerprint = layoutFingerprint; }
    public String getUnparseableSummaryJson() { return unparseableSummaryJson; }
    public void setUnparseableSummaryJson(String unparseableSummaryJson) { this.unparseableSummaryJson = unparseableSummaryJson; }
    public String getActivatedCapabilitiesJson() { return activatedCapabilitiesJson; }
    public void setActivatedCapabilitiesJson(String activatedCapabilitiesJson) { this.activatedCapabilitiesJson = activatedCapabilitiesJson; }
    public Long getImportDurationMs() { return importDurationMs; }
    public void setImportDurationMs(Long importDurationMs) { this.importDurationMs = importDurationMs; }
    @Override public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    @Override public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public Long getOriginalSize() { return originalSize; }
    public void setOriginalSize(Long originalSize) { this.originalSize = originalSize; }
    public Long getStoredSize() { return storedSize; }
    public void setStoredSize(Long storedSize) { this.storedSize = storedSize; }
    @Override public com.finora.imports.storage.CompressionType getCompressionType() { return compressionType; }
    public void setCompressionType(com.finora.imports.storage.CompressionType compressionType) { this.compressionType = compressionType; }
    public String getOriginalMimeType() { return originalMimeType; }
    public void setOriginalMimeType(String originalMimeType) { this.originalMimeType = originalMimeType; }
    @Override public String getEncryptionKeyId() { return encryptionKeyId; }
    public void setEncryptionKeyId(String encryptionKeyId) { this.encryptionKeyId = encryptionKeyId; }

    public UUID getImportJobId() { return importJobId; }
    public void setImportJobId(UUID importJobId) { this.importJobId = importJobId; }
    @Override public byte[] getFileContent() { return fileContent; }
    public void setFileContent(byte[] fileContent) { this.fileContent = fileContent; }
    public LocalDate getStatementPeriodStart() { return statementPeriodStart; }
    public void setStatementPeriodStart(LocalDate statementPeriodStart) { this.statementPeriodStart = statementPeriodStart; }
    public LocalDate getStatementPeriodEnd() { return statementPeriodEnd; }
    public void setStatementPeriodEnd(LocalDate statementPeriodEnd) { this.statementPeriodEnd = statementPeriodEnd; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public void setOpeningBalance(BigDecimal openingBalance) { this.openingBalance = openingBalance; }
    public BigDecimal getClosingBalance() { return closingBalance; }
    public void setClosingBalance(BigDecimal closingBalance) { this.closingBalance = closingBalance; }
    public int getTransactionsImported() { return transactionsImported; }
    public void setTransactionsImported(int transactionsImported) { this.transactionsImported = transactionsImported; }
    public int getTransactionsSkipped() { return transactionsSkipped; }
    public void setTransactionsSkipped(int transactionsSkipped) { this.transactionsSkipped = transactionsSkipped; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }
}
