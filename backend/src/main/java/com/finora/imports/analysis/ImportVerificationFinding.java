package com.finora.imports.analysis;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One verification rule's outcome for one staged section, kept instead of discarded.
 *
 * <p>The four rules — {@code BALANCE_CHAIN}, {@code STATEMENT_TOTALS}, {@code SUMMARY_TOTALS} and
 * {@code COLUMN_AMBIGUITY} — already run on every staged statement. Their findings reach the staging
 * response and are then gone, so "which rules ran on this import, and what did they find" is
 * unanswerable an hour later. That also makes <em>layout → verification rate</em> uncomputable,
 * which {@code import-verification-framework.md} names as the one thing missing from layout
 * intelligence.
 *
 * <h2>Evidence, not knowledge — and deliberately not an aggregate</h2>
 *
 * <p>Rows here are written automatically and never edited, matching {@link StatementAnalysisSession}.
 * There is no overall-status column for the same reason {@code VerificationReport} has none:
 * combining several rules into one verdict needs a weighting policy, and a policy invented before
 * there is anything to calibrate it against is a guess with an authoritative face. This table is
 * what would eventually calibrate one.
 *
 * <h2>Structure and outcome only</h2>
 *
 * <p>The in-memory details map carries opening and closing balances, credit and debit totals, and
 * the raw cell value that made a column ambiguous. That is statement content. V59 is explicit that
 * duplicating any part of a bank statement into a telemetry table is easy to add and very hard to
 * walk back, so {@link ImportVerificationRecorder} rebuilds {@link #detailsJson} from a named
 * allowlist of structural keys rather than stripping the monetary ones out — a detail key a future
 * rule adds is absent here by construction rather than by anyone remembering.
 *
 * <h2>One owner, and which one depends on the path</h2>
 *
 * <p>A finding belongs to one upload attempt. A synchronous upload identifies that attempt with a
 * {@link StatementAnalysisSession}; the asynchronous worker identifies it with an import job and
 * records no analysis session. Exactly one of the two id columns is set, enforced by a CHECK
 * constraint rather than by convention. Inventing a synthetic owner for whichever path lacked one
 * would put a row in an evidence table pointing at something that never happened.
 */
@Entity
@Table(name = "import_verification_findings")
public class ImportVerificationFinding {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "analysis_session_id", updatable = false)
    private UUID analysisSessionId;

    @Column(name = "import_job_id", updatable = false)
    private UUID importJobId;

    /** 0 for a single-account statement; the section's index within a composite one. */
    @Column(name = "section_index", nullable = false, updatable = false)
    private int sectionIndex;

    /** The stable machine identifier the rule publishes ("BALANCE_CHAIN"), never a label. */
    @Column(nullable = false, length = 48, updatable = false)
    private String rule;

    /** This rule's verdict about its own domain: VERIFIED / WARNING / FAILED / NOT_APPLICABLE. */
    @Column(nullable = false, length = 16, updatable = false)
    private String outcome;

    @Column(name = "details_json", columnDefinition = "TEXT", updatable = false)
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ImportVerificationFinding() {
        // JPA
    }

    private ImportVerificationFinding(UUID analysisSessionId, UUID importJobId, int sectionIndex,
                                      String rule, String outcome, String detailsJson) {
        this.analysisSessionId = analysisSessionId;
        this.importJobId = importJobId;
        this.sectionIndex = sectionIndex;
        this.rule = rule;
        this.outcome = outcome;
        this.detailsJson = detailsJson;
    }

    /** A finding from the synchronous upload path, owned by the analysis session it was staged in. */
    public static ImportVerificationFinding forAnalysis(UUID analysisSessionId, int sectionIndex,
                                                        String rule, String outcome, String detailsJson) {
        return new ImportVerificationFinding(analysisSessionId, null, sectionIndex, rule, outcome, detailsJson);
    }

    /** A finding from the asynchronous worker path, which has a job and no analysis session. */
    public static ImportVerificationFinding forJob(UUID importJobId, int sectionIndex,
                                                   String rule, String outcome, String detailsJson) {
        return new ImportVerificationFinding(null, importJobId, sectionIndex, rule, outcome, detailsJson);
    }

    public UUID getId() { return id; }
    public UUID getAnalysisSessionId() { return analysisSessionId; }
    public UUID getImportJobId() { return importJobId; }
    public int getSectionIndex() { return sectionIndex; }
    public String getRule() { return rule; }
    public String getOutcome() { return outcome; }
    public String getDetailsJson() { return detailsJson; }
    public Instant getCreatedAt() { return createdAt; }
}
