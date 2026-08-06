package com.finora.imports.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One upload attempt, remembered whether or not it became an import.
 *
 * <p>The pipeline used to remember only successes: {@code statement_imports} is written inside
 * {@code confirm()}, so a document the parser could not read left no trace at all. That threw away
 * the documents most worth learning from — unknown layouts, unsupported products, new banks, and
 * drift in a layout that used to work. A customer hitting a layout Finora cannot read was, until
 * now, invisible to everyone except that customer.
 *
 * <h2>Evidence, not knowledge</h2>
 * Rows here are written automatically and never edited. They record what was observed, not what
 * anyone concluded. The admin-curated layer — "section 2 of this layout is a fixed deposit,
 * approved, version 3" — is a separate table, deliberately: an observation nobody can revise and a
 * decision that is explicitly somebody's are different kinds of fact, and mixing them makes both
 * untrustworthy. Nothing in this class has a setter for that reason.
 *
 * <h2>What it deliberately does not hold</h2>
 * No statement bytes, no transaction rows, no account numbers, no merchant names. Structure and
 * outcome only. The bytes already have a home ({@code StatementStorage}) with its own retention
 * story, and duplicating any part of a bank statement into a telemetry table is the kind of thing
 * that is easy to add and very hard to walk back.
 */
@Entity
@Table(name = "statement_analysis_sessions")
public class StatementAnalysisSession {

    /** Where the upload came from. Both are recorded; real usage is the more informative half. */
    public enum Source {
        /** An end user importing a statement through the normal flow. */
        CUSTOMER_IMPORT,
        /** An administrator deliberately putting a document through the engine to study it. */
        ADMIN_ANALYSIS,
    }

    /**
     * Deliberately not "SUCCESS": parsing a document is not importing it. A parsed statement can
     * still be abandoned at review, which is a different fact and will get its own outcome once
     * the confirm path records one.
     */
    public enum Outcome { PARSED, FAILED }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 24, updatable = false)
    private String reference;

    /**
     * No foreign key to users, on purpose. A layout observation should outlive the account that
     * happened to produce it — losing the evidence when someone deletes their account would defeat
     * the reason for collecting it, and the row holds nothing personal to protect.
     */
    @Column(name = "user_id", updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 24, updatable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Source source;

    @Column(name = "file_name", length = 255, updatable = false)
    private String fileName;

    @Column(name = "source_format", length = 8, updatable = false)
    private String sourceFormat;

    @Column(name = "byte_size", updatable = false)
    private Long byteSize;

    /** Null when the document never got far enough to be characterised — a wrong PDF password. */
    @Column(name = "layout_fingerprint", length = 128, updatable = false)
    private String layoutFingerprint;

    @Column(nullable = false, length = 16, updatable = false)
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private Outcome outcome;

    /** The ErrorCode that ended it, so failures group by cause rather than by message text. */
    @Column(name = "failure_code", length = 32, updatable = false)
    private String failureCode;

    @Column(name = "failure_detail", columnDefinition = "TEXT", updatable = false)
    private String failureDetail;

    @Column(name = "section_count", updatable = false)
    private Integer sectionCount;

    @Column(name = "duration_ms", updatable = false)
    private Long durationMs;

    /**
     * Transactions extracted. Null means never measured — a document that failed before extraction
     * — which is a different fact from a document that was read and yielded nothing.
     */
    @Column(name = "row_count", updatable = false)
    private Integer rowCount;

    /** {@link ParseDiagnostics#unanchoredReasons()} as JSON, ordered by count descending. */
    @Column(name = "unanchored_reasons_json", columnDefinition = "TEXT", updatable = false)
    private String unanchoredReasonsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected StatementAnalysisSession() {
        // JPA
    }

    private StatementAnalysisSession(String reference, UUID userId, Source source, String fileName,
                                     String sourceFormat, Long byteSize, String layoutFingerprint,
                                     Outcome outcome, String failureCode, String failureDetail,
                                     Integer sectionCount, Long durationMs, Integer rowCount,
                                     String unanchoredReasonsJson) {
        this.rowCount = rowCount;
        this.unanchoredReasonsJson = unanchoredReasonsJson;
        this.reference = reference;
        this.userId = userId;
        this.source = source;
        this.fileName = fileName;
        this.sourceFormat = sourceFormat;
        this.byteSize = byteSize;
        this.layoutFingerprint = layoutFingerprint;
        this.outcome = outcome;
        this.failureCode = failureCode;
        this.failureDetail = failureDetail;
        this.sectionCount = sectionCount;
        this.durationMs = durationMs;
    }

    public static StatementAnalysisSession parsed(String reference, UUID userId, Source source,
                                                  String fileName, String sourceFormat, Long byteSize,
                                                  String layoutFingerprint, Integer sectionCount,
                                                  Long durationMs, Integer rowCount,
                                                  String unanchoredReasonsJson) {
        return new StatementAnalysisSession(reference, userId, source, fileName, sourceFormat,
                byteSize, layoutFingerprint, Outcome.PARSED, null, null, sectionCount, durationMs,
                rowCount, unanchoredReasonsJson);
    }

    public static StatementAnalysisSession failed(String reference, UUID userId, Source source,
                                                   String fileName, String sourceFormat, Long byteSize,
                                                   String layoutFingerprint, String failureCode,
                                                   String failureDetail, Long durationMs,
                                                   Integer rowCount, String unanchoredReasonsJson) {
        // Diagnostics on the FAILED path too, deliberately. A document rejected for extracting
        // nothing is precisely where the histogram earns its keep: "nothing was extracted" is the
        // symptom, and the reason breakdown is the only thing on this row that says why.
        return new StatementAnalysisSession(reference, userId, source, fileName, sourceFormat,
                byteSize, layoutFingerprint, Outcome.FAILED, failureCode, failureDetail, null,
                durationMs, rowCount, unanchoredReasonsJson);
    }

    public UUID getId() { return id; }
    public String getReference() { return reference; }
    public UUID getUserId() { return userId; }
    public Source getSource() { return source; }
    public String getFileName() { return fileName; }
    public String getSourceFormat() { return sourceFormat; }
    public Long getByteSize() { return byteSize; }
    public String getLayoutFingerprint() { return layoutFingerprint; }
    public Outcome getOutcome() { return outcome; }
    public String getFailureCode() { return failureCode; }
    public String getFailureDetail() { return failureDetail; }
    public Integer getSectionCount() { return sectionCount; }
    public Long getDurationMs() { return durationMs; }
    public Integer getRowCount() { return rowCount; }
    public String getUnanchoredReasonsJson() { return unanchoredReasonsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
