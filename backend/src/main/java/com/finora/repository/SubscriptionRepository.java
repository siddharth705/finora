package com.finora.repository;

import com.finora.entity.Subscription;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** At most one row can ever match -- see V99's partial unique index
     *  (idx_subscriptions_one_active_per_user), enforced by the database, not just this query. */
    Optional<Subscription> findByUserIdAndStatusIn(UUID userId, List<String> statuses);

    /** Admin Portal, Subscription Management list. This table grows roughly 1:1 with the user
     *  base (every account gets one on signup, see SubscriptionService.provisionFreeSubscription)
     *  -- SubscriptionService.listAll used to fetch every row unconditionally before this existed,
     *  same fetch-all shape UserRepository.search replaced for Users a while earlier. */
    Page<Subscription> findAllByOrderByCreatedAtDesc(Pageable pageable);

    default Optional<Subscription> findActiveOrTrial(UUID userId) {
        return findByUserIdAndStatusIn(userId, List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIAL));
    }

    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /** DataExportService.buildBundle -- native, bypassing {@code @SQLRestriction} the same way
     *  AccountRepository.findByUserIdIncludingDeleted does: a soft-deleted subscription must
     *  still appear in the export, not silently vanish, the same "purge scope exactly" rule this
     *  class already applies to accounts (see AccountExportEntry's own deleted/deletedAt marker).
     *  Nothing soft-deletes a Subscription today -- every current write is a plain save() -- but
     *  the entity itself supports it ({@code @SQLDelete}), so this reads the true purge scope
     *  rather than assuming the filtered finder above is equivalent to it. */
    @Query(value = "SELECT * FROM subscriptions WHERE user_id = :userId ORDER BY created_at DESC", nativeQuery = true)
    List<Subscription> findByUserIdIncludingDeletedOrderByCreatedAtDesc(@Param("userId") UUID userId);

    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.planId = :planId AND s.status IN ('ACTIVE', 'TRIAL')")
    long countActiveByPlanId(@Param("planId") UUID planId);

    /** AccountPurgeSweepService. Native, bypassing Hibernate's {@code @SQLDelete} entirely -- same
     *  naming discipline as {@code GoalRepository.hardDeleteByUserId}'s own doc comment.
     *  {@code subscription_events}/{@code plan_changes} need no separate cleanup: neither has a
     *  {@code user_id} column, and both cascade automatically via their own
     *  {@code subscription_id ON DELETE CASCADE} (V99). */
    @Modifying
    @Query(value = "DELETE FROM subscriptions WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);

    /** SubscriptionReconciliationSweepService (design spec §6.3) -- the safety net for a missed
     *  {@code subscription.cancelled} webhook. Not scoped to {@code ACTIVE}/{@code TRIAL} on
     *  purpose: {@code status='CANCELLED'} is exactly the state a cancellation already reached. */
    @Query("SELECT s FROM Subscription s WHERE s.autoRenew = false AND s.status = 'CANCELLED' " +
           "AND s.renewalDate < :cutoff")
    List<Subscription> findCancelledSubscriptionsPastPeriodEnd(@Param("cutoff") LocalDate cutoff);
}
