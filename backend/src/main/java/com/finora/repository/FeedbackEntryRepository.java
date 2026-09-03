package com.finora.repository;

import com.finora.entity.FeedbackEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface FeedbackEntryRepository extends JpaRepository<FeedbackEntry, UUID> {

    /**
     * Counts for the admin breakdown, computed in the database rather than by loading every row.
     *
     * <p>The admin surface is a list plus counts by type, context and source — explicitly not
     * trend detection, clustering, or resolution analytics, which need real volume to mean
     * anything and would be a different proposal.
     */
    @Query("""
            SELECT f.type AS type, f.context AS context, f.source AS source, count(f) AS total
              FROM FeedbackEntry f
             GROUP BY f.type, f.context, f.source
            """)
    List<FeedbackBreakdown> countGrouped();

    interface FeedbackBreakdown {
        FeedbackEntry.Type getType();
        FeedbackEntry.Context getContext();
        FeedbackEntry.Source getSource();
        long getTotal();
    }

    /**
     * Account purge. Needed for the same reason {@code SupportTicketRepository.deleteByUserId} is:
     * {@code feedback_entries.user_id} carries {@code ON DELETE CASCADE}, but
     * {@code AccountPurgeSweepService.purgeOne} anonymizes the {@code users} row instead of
     * deleting it, so that cascade never fires. Without this being called from {@code purgeOne},
     * a deleted user's feedback survives the purge.
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically} for the reason spelled out on
     * {@code SupportTicketRepository.deleteByUserId}: a bulk JPQL delete leaves the persistence
     * context holding rows the database no longer has.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM FeedbackEntry f WHERE f.userId = :userId")
    int deleteByUserId(@Param("userId") UUID userId);
}
