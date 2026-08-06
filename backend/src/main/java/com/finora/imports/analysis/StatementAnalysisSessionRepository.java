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

    /** Newest first — what an admin opening the diagnostics view wants to see. */
    List<StatementAnalysisSession> findAllByOrderByCreatedAtDesc(Pageable pageable);

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

    @Query("SELECT COUNT(DISTINCT s.layoutFingerprint) FROM StatementAnalysisSession s WHERE s.layoutFingerprint IS NOT NULL")
    long countDistinctLayouts();
}
