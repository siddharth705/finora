package com.finora.imports.analysis;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementAnalysisSessionRepository extends JpaRepository<StatementAnalysisSession, UUID> {

    /**
     * AccountPurgeSweepService -- the one deliberate exception to this table's "written once,
     * never edited" design (see {@link StatementAnalysisSession}'s own class doc, which claims
     * "the row holds nothing personal to protect"). That claim doesn't survive contact with two of
     * its own columns: {@code file_name} is the exact filename the user uploaded, and
     * {@code failure_detail} can hold a fragment of the document itself (see {@code
     * ImportDto.ImportFailureSummaryDto}'s own doc comment on why that field is admin/debug-only
     * and never reaches a customer response). Clears exactly those two plus {@code user_id} --
     * {@code layout_fingerprint}/{@code outcome}/{@code failure_code}/{@code section_count}/{@code
     * duration_ms}/{@code source}/{@code reference}/{@code created_at} are the actual
     * layout-intelligence signal this table exists to aggregate, and none of them are personal.
     *
     * <p>Native, not a JPQL bulk update: every column here is {@code updatable = false} by design
     * (the entity has no setters at all), so a native statement is what makes bypassing that
     * immutability, on this one purge-only path, visible rather than accidental.
     */
    @Modifying
    @Query(value = "UPDATE statement_analysis_sessions SET user_id = NULL, file_name = NULL, failure_detail = NULL WHERE user_id = :userId",
            nativeQuery = true)
    void anonymizeByUserId(@Param("userId") UUID userId);

    /**
     * The reference counter, from a database sequence rather than a count of existing rows.
     *
     * <p>{@code SELECT count(*) + 1} would hand the same number to two concurrent uploads, and the
     * unique constraint would then fail one of them — turning a telemetry collision into a lost
     * evidence row for whichever import happened to lose the race.
     */
    @Query(value = "SELECT nextval('statement_analysis_reference_seq')", nativeQuery = true)
    long nextReferenceNumber();

    Optional<StatementAnalysisSession> findByReference(String reference);

    /**
     * The evidence row for a staging session, when one was recorded.
     *
     * <p>A list rather than an {@code Optional}: there is no unique constraint on the column and
     * inventing one would turn a duplicated telemetry write -- a measurement problem -- into a
     * rejected upload. Ordered newest first so a trace shows the most recent observation if a
     * session ever does acquire two.
     */
    List<StatementAnalysisSession> findByImportSessionIdOrderByCreatedAtDesc(UUID importSessionId);

    /** Newest first — what an admin opening the diagnostics view wants to see. */
    List<StatementAnalysisSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * A user's own recent failed imports — Premium Import Reliability v1, §2.1's durable failure
     * record. {@code source} is filtered to {@code CUSTOMER_IMPORT} deliberately: {@code userId}
     * on an {@code ADMIN_ANALYSIS} row is the admin who ran the probe, not a customer, but an
     * admin is still a user, and this endpoint must not surface their own diagnostic probing back
     * to them as if it were a real failed statement import.
     */
    List<StatementAnalysisSession> findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(
            java.util.UUID userId, StatementAnalysisSession.Source source,
            StatementAnalysisSession.Outcome outcome, Pageable pageable);

    /**
     * The question this table was built to answer: which layouts defeat the parser, and how often.
     * Grouped by fingerprint and failure code together, because "this layout fails" and "this
     * layout fails FOR THIS REASON" lead to different fixes.
     */
    @Query("""
            SELECT s.layoutFingerprint, s.failureCode, COUNT(s)
            FROM StatementAnalysisSession s
            WHERE s.outcome = com.finora.imports.analysis.StatementAnalysisSession$Outcome.FAILED
            GROUP BY s.layoutFingerprint, s.failureCode
            ORDER BY COUNT(s) DESC
            """)
    List<Object[]> failureCountsByLayout();

    /**
     * How many customer imports failed, by reason AND by layout fingerprint, since some point in
     * time -- Premium Import Reliability v1, §4.9's failure analytics. {@code source} is filtered
     * to {@code CUSTOMER_IMPORT} deliberately, unlike {@link #failureCountsByLayout}: an admin's
     * own diagnostic probing must not inflate a count meant to represent customer experience.
     *
     * <p>Grouped by {@code (failureCode, layoutFingerprint)} rather than {@code failureCode} alone
     * so {@link StatementAnalysisReportService#failureCounts} can derive BOTH the per-code total
     * (sum the groups) AND a best-effort bank (the dominant non-null fingerprint per code, resolved
     * through the layout registry) from one scan -- an earlier version of this query ran as two
     * separate, near-identical full scans of the same window, one to a fault the other's own row
     * shape didn't need. A null fingerprint (the document failed before it could be characterised)
     * is a real row and stays in the result, since it still counts toward the code's total; it is
     * simply never a candidate when the caller picks a dominant fingerprint.
     *
     * <p>{@code since} has no default and no caller-side fallback: an unbounded scan of a table
     * that only grows is a cost this method should never silently absorb on a caller's behalf.
     *
     * <p>{@code ORDER BY} carries an explicit tiebreaker ({@code s.layoutFingerprint ASC}) after
     * the count, not just for cosmetic determinism: two distinct fingerprints tying on count for
     * the same failure code is a realistic, low-volume-system case, and without a secondary sort
     * key Postgres does not guarantee which tied row comes back first -- the caller's "dominant
     * fingerprint" pick would otherwise silently flip between two calls against unchanged data.
     */
    @Query("""
            SELECT s.failureCode, s.layoutFingerprint, COUNT(s), MAX(s.createdAt)
            FROM StatementAnalysisSession s
            WHERE s.outcome = com.finora.imports.analysis.StatementAnalysisSession$Outcome.FAILED
              AND s.source = com.finora.imports.analysis.StatementAnalysisSession$Source.CUSTOMER_IMPORT
              AND s.createdAt >= :since
            GROUP BY s.failureCode, s.layoutFingerprint
            ORDER BY COUNT(s) DESC, s.layoutFingerprint ASC
            """)
    List<Object[]> failureCodeLayoutCounts(@Param("since") Instant since);

    long countByOutcome(StatementAnalysisSession.Outcome outcome);

    /**
     * How often this exact layout has been seen, and how often it defeated the parser.
     *
     * <p>The pair is what stops an investigation being repeated. Opening one analysis and reading
     * "this fingerprint has been seen 12 times and failed 11 of them" is a different situation
     * from "seen once" — the first is a layout the engine cannot read, the second is a document
     * nobody has looked at yet, and they deserve different amounts of attention.
     */
    long countByLayoutFingerprint(String layoutFingerprint);

    long countByLayoutFingerprintAndOutcome(String layoutFingerprint,
                                            StatementAnalysisSession.Outcome outcome);

    @Query("SELECT COUNT(DISTINCT s.layoutFingerprint) FROM StatementAnalysisSession s WHERE s.layoutFingerprint IS NOT NULL")
    long countDistinctLayouts();
}
