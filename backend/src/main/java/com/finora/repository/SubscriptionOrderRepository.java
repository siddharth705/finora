package com.finora.repository;

import com.finora.entity.SubscriptionOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionOrderRepository extends JpaRepository<SubscriptionOrder, UUID> {
    Optional<SubscriptionOrder> findByRazorpaySubscriptionId(String razorpaySubscriptionId);
}
