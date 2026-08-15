package com.finora.repository;

import com.finora.entity.ImportSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportSessionRepository extends JpaRepository<ImportSession, UUID> {

    List<ImportSession> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

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
     */
    @Modifying
    @Query("UPDATE ImportSession s SET s.status = 'CONFIRMED', s.confirmedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id AND s.status = 'STAGED'")
    int claimForConfirmation(@Param("id") UUID id);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity (no
     *  lifecycle state to preserve, unlike StatementImport). Also frees any object this session
     *  was the sole reference for, for StatementStorageSweepService to eventually reclaim. */
    void deleteByUserId(UUID userId);
}
