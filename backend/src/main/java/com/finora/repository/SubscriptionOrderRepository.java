package com.finora.repository;

import com.finora.entity.SubscriptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {
    Optional<SubscriptionOrder> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /** Subscription billing V3 (design spec review, §0.5 of the V3 plan). What both
     *  {@code mySubscription} (read) and {@code resumableOrderOrGuard} (checkout/upgrade) use to
     *  find a user's still-in-flight checkout -- "first" only matters if more than one PENDING
     *  order ever exists for one user, which itself would be its own bug; ordering by
     *  createdAt desc is defensive, not load-bearing. */
    Optional<SubscriptionOrder> findFirstByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, String status);

    /** Admin Portal, Subscription Health (Plan 3 review) -- how many checkouts are currently
     *  in-flight platform-wide, not per user. */
    long countByStatus(String status);
}
