package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * D-28 PR4-A. The Product-approved Free/Plus/Premium taxonomy (Billing Plan Taxonomy Decision,
 * 2026-08-12) -- seeded from {@code frontend/src/pages/landing/plans.ts}'s own tier set (V99), not
 * authored independently, so the two can't drift into describing different products. Not
 * extending BaseEntity: plans aren't soft-deleted (see {@link #active}) or optimistically locked
 * any more than {@link FeatureFlag} is -- same reasoning as that entity's own class comment.
 */
@Entity
@Table(name = "plans")
public class Plan {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(nullable = false, length = 50)
    private String name;

    /** Null means "not yet purchasable" -- matches {@code plans.ts}'s own rule that price may
     *  only be set on an 'available' plan. Only Free has a price today. */
    @Column
    private BigDecimal price;

    @Column(name = "billing_cycle", length = 20)
    private String billingCycle;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
