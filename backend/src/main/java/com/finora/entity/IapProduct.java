package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V4. Resolves a RevenueCat product_id (+ platform, since nothing requires
 * App Store Connect and Play Console product ids to be globally distinct from each other) to a
 * Fynora plan/cycle -- the same lookup-not-branch role billing_prices plays for Razorpay's
 * plan_id/billing_cycle -> razorpay_plan_id mapping.
 */
@Entity
@Table(name = "iap_products")
public class IapProduct {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "provider_product_id", nullable = false, length = 100)
    private String providerProductId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(nullable = false, length = 10)
    private String platform;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getProviderProductId() { return providerProductId; }
    public void setProviderProductId(String providerProductId) { this.providerProductId = providerProductId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getPlatform() { return platform; }
    public void setPlatform(String platform) { this.platform = platform; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
