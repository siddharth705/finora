package com.finora.repository;

import com.finora.entity.SubscriptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** AccountPurgeSweepService. Native, bypassing Hibernate entirely -- same naming discipline
     *  as {@code PaymentRepository.hardDeleteByUserId}. V157 also gives {@code subscription_orders}
     *  its own {@code ON DELETE CASCADE}, but that alone never fires: {@code purgeOne} anonymizes
     *  users, it never issues a raw {@code DELETE FROM users} for the CASCADE to trigger off of --
     *  same trap {@code AccountPurgeSweepService}'s own comments already call out for V125/V137. */
    @Modifying
    @Query(value = "DELETE FROM subscription_orders WHERE user_id = :userId", nativeQuery = true)
    void hardDeleteByUserId(@Param("userId") UUID userId);
}
