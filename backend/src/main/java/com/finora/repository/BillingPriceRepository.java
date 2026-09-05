package com.finora.repository;

import com.finora.entity.BillingPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BillingPriceRepository extends JpaRepository<BillingPrice, UUID> {
    Optional<BillingPrice> findByPlanIdAndBillingCycleAndActiveTrue(UUID planId, String billingCycle);
}
