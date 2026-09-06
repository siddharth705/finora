package com.finora.repository;

import com.finora.entity.SubscriptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {
    Optional<SubscriptionOrder> findByRazorpaySubscriptionId(String razorpaySubscriptionId);

    /** Subscription billing V2. Guards against a double-submit racing to create a second live
     *  Razorpay subscription for the same user before the first order's activation webhook lands
     *  -- see {@code BillingCheckoutService.ensureNoOrderInFlight}. */
    boolean existsByUserIdAndStatus(UUID userId, String status);
}
