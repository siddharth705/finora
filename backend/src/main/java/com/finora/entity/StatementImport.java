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
public class StatementImport extends BaseEntity {

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

    // Raw CSV bytes — kept so "Re-import Statement" can replay the exact file and "Download
    // Original File" has something to serve. Lazy-fetched: every list/history view only needs
    // the metadata columns, not the file bytes, so this shouldn't ride along on those queries.
    // Deliberately NOT @Lob: on PostgreSQL, Hibernate maps @Lob byte[] to the `oid` large-object
    // type, but the V10 migration created a plain `bytea` column (simpler — no separate large
    // object storage/cleanup to manage). JdbcTypeCode(VARBINARY) is what actually matches `bytea`.
    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "file_content", nullable = false)
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

    @Column(nullable = false)
    private String status = "COMPLETED";

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt = Instant.now();

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
    public String getActivatedCapabilitiesJson() { return activatedCapabilitiesJson; }
    public void setActivatedCapabilitiesJson(String activatedCapabilitiesJson) { this.activatedCapabilitiesJson = activatedCapabilitiesJson; }
    public byte[] getFileContent() { return fileContent; }
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getImportedAt() { return importedAt; }
    public void setImportedAt(Instant importedAt) { this.importedAt = importedAt; }
}
