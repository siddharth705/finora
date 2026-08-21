package com.finora.repository;

import com.finora.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {

    /** At most one row can ever match -- see V99's partial unique index
     *  (idx_subscriptions_one_active_per_user), enforced by the database, not just this query. */
    Optional<Subscription> findByUserIdAndStatusIn(UUID userId, List<String> statuses);

    default Optional<Subscription> findActiveOrTrial(UUID userId) {
        return findByUserIdAndStatusIn(userId, List.of(Subscription.STATUS_ACTIVE, Subscription.STATUS_TRIAL));
    }

    List<Subscription> findByUserIdOrderByCreatedAtDesc(UUID userId);

    @Query("SELECT COUNT(s) FROM Subscription s WHERE s.planId = :planId AND s.status IN ('ACTIVE', 'TRIAL')")
    long countActiveByPlanId(@Param("planId") UUID planId);
}
