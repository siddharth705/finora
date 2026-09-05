package com.finora.repository;

import com.finora.entity.HeldStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HeldStatementRepository extends JpaRepository<HeldStatement, UUID> {

    /** The reference an operator quotes. */
    Optional<HeldStatement> findByHeldId(String heldId);

    /** One import is held at most once -- {@code import_job_id} is UNIQUE in V144. */
    Optional<HeldStatement> findByImportJobId(UUID importJobId);

    Page<HeldStatement> findByStatusIn(Collection<HeldStatement.Status> statuses, Pageable pageable);

    long countByStatusIn(Collection<HeldStatement.Status> statuses);

    /**
     * The queue, narrowed by {@link com.finora.service.HeldStatementFilter}. {@code openStatuses}
     * is not part of the filter itself -- it is the same permanent floor {@link #findByStatusIn}
     * applies, so a null {@code status} filter still never surfaces a resolved hold; a non-null one
     * narrows further within that set.
     *
     * <p>{@code :x IS NULL OR ...} rather than {@code JpaSpecificationExecutor}, matching the
     * idiom {@code SupportTicketRepository.findForAdmin} and {@code TransactionRepository} already
     * use for this codebase's other optional-filter admin queues -- not a second idiom introduced
     * for one screen.
     *
     * <p>{@code :olderThan} is CAST to {@code timestamp} in its own {@code IS NULL} check --
     * {@code AuditLogRepository.search}'s own doc already caught and documents this exact
     * PostgreSQL/pgjdbc gap (SQLState 42P18, {@code could not determine data type of parameter}):
     * a named parameter used twice becomes two separate placeholders, and the bare {@code IS NULL}
     * occurrence has no type context of its own to infer from when the other occurrence is a
     * {@code timestamptz} comparison. The enum, string and UUID filters above do not need the same
     * cast -- only the timestamp comparison hits this.
     */
    @Query("""
            SELECT h FROM HeldStatement h
             WHERE h.status IN :openStatuses
               AND (:status IS NULL OR h.status = :status)
               AND (:bankName IS NULL OR h.bankName = :bankName)
               AND (CAST(:olderThan AS timestamp) IS NULL OR h.createdAt <= :olderThan)
               AND (:assignedEngineerId IS NULL OR h.assignedEngineerId = :assignedEngineerId)
             ORDER BY h.createdAt ASC
            """)
    Page<HeldStatement> findForAdmin(@Param("openStatuses") Collection<HeldStatement.Status> openStatuses,
                                     @Param("status") HeldStatement.Status status,
                                     @Param("bankName") String bankName,
                                     @Param("olderThan") Instant olderThan,
                                     @Param("assignedEngineerId") UUID assignedEngineerId,
                                     Pageable pageable);

    /**
     * The raw sequence value. Formatting is {@code HeldStatementIdGenerator}'s job -- the same
     * split {@code StatementAnalysisRecorder} uses for its {@code SA-} references.
     *
     * <p>{@code nextval} is transactional-but-not-rollback-safe by design: a rolled-back hold burns
     * its number rather than reissuing it. Gaps in the sequence are the correct trade -- a reused
     * reference would point at two different statements.
     */
    @Query(value = "SELECT nextval('held_statement_reference_seq')", nativeQuery = true)
    long nextHeldSequence();

    // --- telemetry (Plan 4) ---------------------------------------------------------------------

    /** One row per (status, count) -- the resolution breakdown. Native, matching {@code
     *  ImportJobRepository}'s own telemetry-query idiom in this codebase. */
    @Query(value = "SELECT status, count(*) FROM held_statements GROUP BY status", nativeQuery = true)
    List<Object[]> telemetryStatusCounts();

    /**
     * How many resolved holds were explicitly marked false positive. {@code FILTER} matches this
     * codebase's own established telemetry idiom ({@code ImportJobRepository.telemetryFlagCounts}).
     *
     * <p>{@code resolvedStatuses} is passed in rather than hardcoded as {@code status IN ('IMPORTED',
     * 'REJECTED')} in the SQL string, so this query's idea of "resolved" can never drift from
     * {@code HeldStatement.Status.RESOLVED}'s own {@code EnumSet} -- the caller passes that set's
     * {@code .name()}s, not a second, hand-maintained copy of the same two strings. See Plan 4's
     * own Decisions table.
     */
    @Query(value = """
           SELECT count(*) FILTER (WHERE status = ANY(:resolvedStatuses)),
                  count(*) FILTER (WHERE false_positive = true)
             FROM held_statements
           """, nativeQuery = true)
    List<Object[]> telemetryFalsePositiveCounts(@Param("resolvedStatuses") String[] resolvedStatuses);

    /** Which trust condition fired, across every hold that has any recorded (older holds predating
     *  V152 have null and are correctly excluded, not counted as zero). {@code unnest} rather than
     *  one FILTER per category, so a category Brief Phase 11 adds later needs no new query -- see
     *  Plan 4's own Decisions table. */
    @Query(value = """
           SELECT category, count(*)
             FROM held_statements, unnest(hold_reason_categories) AS category
            GROUP BY category
           """, nativeQuery = true)
    List<Object[]> telemetryCategoryCounts();

    /**
     * Median hours from created to resolved, over every hold that has resolved. Median, not mean --
     * see Plan 4's own Decisions table for why. Null (via a single-row, single-null-column result
     * when nothing has resolved) rather than a division-by-zero exception.
     *
     * <p>Intentionally live and unsummarized, matching every other query in this class -- computed
     * fresh from the full table on every request, nothing cached or scheduled. {@code
     * PERCENTILE_CONT} over the whole table is one of the more expensive queries this class runs;
     * fine at this system's current and expected near-term volume, but if {@code held_statements}
     * ever reaches the scale where this becomes a real cost, revisit rather than assume it stays
     * cheap forever -- flagged explicitly during Plan 4's review so it's a documented trade-off,
     * not a surprise later.
     */
    @Query(value = """
           SELECT PERCENTILE_CONT(0.5) WITHIN GROUP (
                      ORDER BY EXTRACT(EPOCH FROM (resolved_at - created_at)) / 3600.0)
             FROM held_statements
            WHERE resolved_at IS NOT NULL
           """, nativeQuery = true)
    Double telemetryMedianResolutionHours();
}
