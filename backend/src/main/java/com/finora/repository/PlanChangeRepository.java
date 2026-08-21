package com.finora.repository;

import com.finora.entity.PlanChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanChangeRepository extends JpaRepository<PlanChange, UUID> {
    List<PlanChange> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);
}
