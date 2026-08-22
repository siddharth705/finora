package com.finora.repository;

import com.finora.entity.PlanChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PlanChangeRepository extends JpaRepository<PlanChange, UUID> {
    List<PlanChange> findBySubscriptionIdOrderByCreatedAtDesc(UUID subscriptionId);

    /** DataExportService.buildBundle -- one batched query across every one of a user's
     *  subscriptions (a user can have more than one historical row), not one
     *  findBySubscriptionIdOrderByCreatedAtDesc call per subscription. */
    List<PlanChange> findBySubscriptionIdInOrderByCreatedAtDesc(List<UUID> subscriptionIds);
}
