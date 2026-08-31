package com.finora.repository;

import com.finora.entity.SubscriptionEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SubscriptionEventRepository extends JpaRepository<SubscriptionEvent, UUID> {
    List<SubscriptionEvent> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);
}
