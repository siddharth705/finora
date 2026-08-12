package com.finora.imports.analysis;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementAnalysisSessionRepository extends JpaRepository<StatementAnalysisSession, UUID> {

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
