package com.finora.repository;

import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportSessionRepository extends JpaRepository<ImportSession, UUID> {

    List<ImportSession> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

    /**
     * The Gmail review queue (C5.4) -- every source's own sessions filtered independently, rather
     * than reusing {@link #findByUserIdAndStatusOrderByCreatedAtDesc} and filtering by
     * {@code source} in Java, so the "how many need review" count {@code GmailReviewService} and
     * the connection-status endpoint both need can be a database count, not a full row fetch.
     */
    List<ImportSession> findByUserIdAndSourceAndStatusOrderByCreatedAtDesc(
            UUID userId, String source, String status);

    long countByUserIdAndSourceAndStatus(UUID userId, String source, String status);

    /**
     * This user's own live (STAGED) session for this exact document, if one exists -- backs
     * {@code ImportSessionService.findLiveSessionByContentHash}, the app-level half of
     * V79__import_session_stage_idempotency.sql's duplicate-upload protection. Served by the same
     * partial unique index that migration creates ({@code idx_import_sessions_live_content}), which
     * is why this query filters on exactly the columns and status that index covers.
     */
    Optional<ImportSession> findFirstByUserIdAndContentHashAndStatusOrderByCreatedAtDesc(
            UUID userId, String contentHash, String status);

    /**
     * Expired sessions from ANY user, oldest first, bounded by the caller's page size.
     *
     * <p>Backs the opportunistic cleanup in {@code ImportSessionService} -- see that class's doc
     * comment for why this isn't a {@code @Scheduled} sweep.
     *
     * <p>Bug fix: the cleanup used to be scoped to the acting user
     * ({@code findByUserIdAndExpiresAtBefore}), so an expired session was only ever deleted when
     * THAT SAME user started another import. A user who imported once and never came back -- the
     * one-time and trial population, by definition the largest source of abandoned sessions --
     * left the row and the raw statement bytes inside it in the database permanently, and nothing
     * else ever removed them. The 48-hour retention the TTL states was not enforced for precisely
     * the population most likely to need it, on the most sensitive data this product holds.
     *
     * <p>Platform-wide but explicitly paged, which is what keeps the original reasoning intact:
     * the scoping existed to avoid "a full-table scan every time anyone imports anything", and a
     * bounded, index-ordered slice is not that. Any backlog drains over subsequent imports rather
     * than in one unbounded delete.
     */
    List<ImportSession> findByExpiresAtBeforeOrderByExpiresAtAsc(Instant now, Pageable limit);

    /**
     * The same expired slice, minus any session an unresolved review still depends on.
     *
     * <p>Reference counting against {@code import_jobs}, the same shape
     * {@code StatementStorageSweepService} already uses to decide whether a stored object is still
     * live -- and for the same reason. That service was deliberately built so a held job's PDF
     * survives; nothing gave the STAGED ROWS the same protection, so a hold outliving the 48-hour
     * TTL lost the very rows the reviewer was judging. Approving it afterwards marked the job
     * COMPLETED against a deleted session and told the user their statement was ready.
     *
     * <p>Expressed as NOT EXISTS over a status parameter rather than a hardcoded enum, so the
     * protected set is decided by the caller and stays visible next to the reasoning for it.
     *
     * <p>The exemption is not a pin: it lasts exactly as long as the review does. Once the hold is
     * released or rejected the job leaves that status, and the row becomes sweepable on the next
     * pass like any other expired session.
     */
    @Query("""
            SELECT s FROM ImportSession s
             WHERE s.expiresAt < :now
               AND NOT EXISTS (SELECT 1 FROM ImportJob j
                                WHERE j.importSessionId = s.id
                                  AND j.status IN :protectedStatuses)
             ORDER BY s.expiresAt ASC
            """)
    List<ImportSession> findSweepableExpiredSessions(
            @Param("now") Instant now,
            @Param("protectedStatuses") Collection<ImportJob.Status> protectedStatuses,
            Pageable limit);

    /**
     * Whether any row -- STAGED or CONFIRMED, expired or not -- still references this object key.
     *
     * <p>BH-017. {@code ImportSession} carries no soft delete, so "a row with this key exists" IS
     * "this key is currently referenced"; unlike the equivalent check on
     * {@code StatementImportRepository}, there is no lifecycle state to exclude. Deliberately not
     * filtered by status: a CONFIRMED session's row still exists until its own 48h TTL sweeps it,
     * during which it references the same object its resulting {@code StatementImport} does, and a
     * STAGED one is, by definition, an active reference. See
     * {@code StatementStorageSweepService}, which OR's this against
     * {@code StatementImportRepository.existsByObjectKey}.
     *
     * <p><b>BH-039: deliberately global, never add a {@code userId} parameter.</b> Content
     * addressing has no tenant prefix ({@code ContentAddress}'s class doc) -- two different users
     * who upload byte-identical documents share one object, by design. Scoping this by user would
     * make the sweep delete another tenant's only copy of a shared document the moment THIS
     * tenant's reference disappears. {@code StatementStorageSweepServiceIT
     * .sweep_doesNotReclaimAnObjectStillReferencedByAnotherTenantsLiveRow} is the regression test
     * for exactly this.
     */
    boolean existsByObjectKey(String objectKey);

    /**
     * Atomically flips STAGED -> CONFIRMED, returning the number of rows actually updated (0 or
     * 1). This -- not a plain read-then-save -- is what actually closes the double-submission
     * race a double-click or a retried request could otherwise trigger: two concurrent
     * confirmSession() calls both reading status=STAGED before either commits would both proceed
     * to import a full, duplicate set of transactions, since inserting new Transaction rows
     * doesn't conflict with anything at the database level the way an update to the same row
     * would. A bare @Version on ImportSession wouldn't have prevented that either -- optimistic
     * locking only protects this row's own update, not the separate inserts each concurrent call
     * makes before it. Claiming the session with this atomic, conditional UPDATE as the very
     * first step -- before any import work happens at all -- means only one of two concurrent
     * calls ever proceeds; the loser sees 0 rows affected and is rejected immediately, before it
     * touches the transactions table.
     *
     * <p>Native, not JPQL, since joining to {@code held_statements} through {@code import_jobs}
     * needs a real SQL join -- neither entity carries a mapped association to the other (both link
     * by a plain {@code UUID} column), which JPQL path expressions can't cross.
     *
     * <p>The {@code NOT EXISTS} clause closes a second, narrower race the status-only version of
     * this query left open: a plain read of the hold state (however recent) followed by this
     * UPDATE has a real gap between them for a concurrent transaction to commit a NEW hold in, and
     * a status-only {@code WHERE} clause has no way to see that. Folding the same "does a blocking
     * hold exist" condition {@code ImportSessionService#sessionsBlockedByTrustReview} already
     * checks into the atomic UPDATE itself means the two checks either both see the hold or
     * neither does -- there is no window between them for one to miss it. {@code IMPORTED} is the
     * sole non-blocking status, matching {@code
     * HeldStatementRepository.findByImportJobIdInAndStatusNot}'s own reasoning for the same
     * fail-closed default.
     */
    // clearAutomatically: a native bulk UPDATE writes through JDBC directly, bypassing the
    // persistence context entirely -- Hibernate has no way to know the ImportSession entity it
    // already cached from an EARLIER read in this same transaction (getOwnedSession's own
    // findById, always called first) is now stale. Without this, the findById at the end of
    // ImportSessionService.claimForConfirmation returns that same cached, pre-update object --
    // still STAGED, still confirmedAt == null -- to a caller this method's own contract promises
    // "the confirmed session" to. Confirmed as a real, reproducible defect against a live Postgres
    // instance (ImportSessionRepositoryIT), not a hypothetical: the exact scenario every
    // mock-based test in this codebase was structurally incapable of catching, since a mock has no
    // first-level cache to go stale in the first place.
    //
    // flushAutomatically: the companion half of the same problem in the other direction -- the
    // STAGED row this query's own WHERE clause depends on may still be an unflushed pending INSERT
    // in the SAME persistence context (createSession() and confirmSession() are not guaranteed to
    // be two separate transactions), and Hibernate's auto-flush heuristics have no entity-level
    // reasoning to apply to a native query it cannot introspect the tables of.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
           UPDATE import_sessions s
              SET status = 'CONFIRMED', confirmed_at = now()
            WHERE s.id = :id
              AND s.status = 'STAGED'
              AND NOT EXISTS (
                    SELECT 1 FROM import_jobs j
                     JOIN held_statements hs ON hs.id = j.held_statement_id
                    WHERE j.import_session_id = s.id
                      AND hs.status <> 'IMPORTED'
                  )
           """, nativeQuery = true)
    int claimForConfirmation(@Param("id") UUID id);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity (no
     *  lifecycle state to preserve, unlike StatementImport). Also frees any object this session
     *  was the sole reference for, for StatementStorageSweepService to eventually reclaim. */
    void deleteByUserId(UUID userId);

    /** DataExportService -- every session this user has ever staged, any status/kind, expired or
     *  not. Deliberately unfiltered, unlike every other finder on this repository: the resume-flow
     *  finders above exist to answer "what can I still act on", but an export owes the user
     *  everything on record, including sessions that already confirmed or expired. */
    List<ImportSession> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
