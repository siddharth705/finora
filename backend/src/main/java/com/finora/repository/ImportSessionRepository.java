package com.finora.repository;

import com.finora.entity.ImportSession;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ImportSessionRepository extends JpaRepository<ImportSession, UUID> {

    List<ImportSession> findByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

    // Backs the opportunistic cleanup in ImportSessionService.createSession() -- see that
    // class's doc comment for why this isn't a @Scheduled sweep.
    List<ImportSession> findByUserIdAndExpiresAtBefore(UUID userId, Instant now);

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

    // ---- Phase 3 backfill ----
    // Ids only; the backfill loads one row at a time. See StatementImportRepository's equivalent.
    @Query(value = "SELECT id FROM import_sessions WHERE content_hash IS NULL", nativeQuery = true)
    List<UUID> findIdsWithoutContentAddress(Pageable pageable);

    @Query(value = "SELECT COUNT(*) FROM import_sessions WHERE content_hash IS NULL", nativeQuery = true)
    long countWithoutContentAddress();
}
