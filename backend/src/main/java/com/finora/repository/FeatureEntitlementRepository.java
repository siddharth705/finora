package com.finora.repository;

import com.finora.entity.FeatureEntitlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeatureEntitlementRepository extends JpaRepository<FeatureEntitlement, UUID> {
    Optional<FeatureEntitlement> findByPlanIdAndFeatureKey(UUID planId, String featureKey);
    List<FeatureEntitlement> findByPlanId(UUID planId);
}
