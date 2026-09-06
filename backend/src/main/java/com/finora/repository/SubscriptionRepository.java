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

    /** Admin Portal, Subscription Management list -- real customer accounts only. Two things a
     *  plain unfiltered fetch-all wouldn't catch, both bugs found from a live screenshot of the
     *  admin page (this method replaces an earlier plain {@code findAllByOrderByCreatedAtDesc}
     *  that had neither filter):
     *  <p>1. Every account gets a free subscription on signup regardless of
     *  {@code account_scope} (provisionFreeSubscription has no scope branch) -- so ADMIN-scope
     *  accounts (e.g. the bootstrap installer, an admin@ account under V52's dual-identity design)
     *  show up here as if they were paying customers. Scoped to {@code accountScope = 'USER'}.
     *  <p>2. A subscription row surviving for a {@code status = 'DELETED'} user means
     *  {@code AccountPurgeSweepService.purgeOne}'s own hard-delete of that row never happened for
     *  this user (current purgeOne always deletes it in the same transaction as anonymizing the
     *  user -- see that class's own ordering doc) -- a pre-existing data gap, not something this
     *  query can retroactively clean up on its own, but showing a deleted account's stale row as
     *  an "ACTIVE" plan is actively misleading regardless of how it got there. Excluded here as a
     *  display-layer safety net independent of any backfill that clears the underlying rows. */
    @Query("SELECT s FROM Subscription s WHERE s.userId IN " +
           "(SELECT u.id FROM User u WHERE u.accountScope = 'USER' AND u.status != 'DELETED') " +
           "ORDER BY s.createdAt DESC")
    Page<Subscription> findForCustomerAccountsOrderByCreatedAtDesc(Pageable pageable);

    default Optional<Subscription> findActiveOrTrial(UUID userId) {
        return findByUserIdAndStatusIn(userId, List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIAL));
    }

    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Subscription> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /** RevenueCat/store analog of {@code findByRazorpaySubscriptionId} -- looks the subscription up
     *  by its stable external id, independent of the row's current status. RevenueCatWebhookDispatcher
     *  uses this for every event except INITIAL_PURCHASE, exactly mirroring why
     *  RazorpayWebhookDispatcher's handleCharged/handlePending/handleHalted/handleCancelled never
     *  use {@code findActiveOrTrial}: a subscription in PAST_DUE (set by a billing-issue/pending
     *  event) must still be reachable by the RENEWAL that reactivates it or the EXPIRATION that
     *  finally downgrades it -- both of which findActiveOrTrial's ACTIVE/TRIAL filter would miss. */
    Optional<Subscription> findByRevenuecatOriginalTransactionId(String revenuecatOriginalTransactionId);

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

    /** Admin Portal, Subscription Health (Plan 3 review). One call per status shown on that
     *  dashboard -- five small COUNT queries rather than one grouped query, matching this
     *  interface's existing style of a plain derived method per need over a single do-everything
     *  query. Same two gaps as {@link #findForCustomerAccountsOrderByCreatedAtDesc} above (ADMIN-
     *  scope accounts and orphaned DELETED-user rows both counted as if they were live customer
     *  plans) -- these stat cards sit directly above the list that method already fixed, so an
     *  unfiltered count here would visibly disagree with the now-filtered list underneath it. */
    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.status = :status AND s.userId IN " +
           "(SELECT u.id FROM User u WHERE u.accountScope = 'USER' AND u.status != 'DELETED')")
    long countForCustomerAccountsByStatus(@Param("status") String status);

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
