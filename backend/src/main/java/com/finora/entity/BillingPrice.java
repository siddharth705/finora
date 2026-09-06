package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V1. Price is keyed by (plan, billing cycle), not by plan alone --
 * entitlements stay billing-cycle-agnostic (EntitlementService never reads this table), only
 * checkout and renewal do. FREE has no row here; it is never checked out through Razorpay.
 */
@Entity
@Table(name = "billing_prices")
public class BillingPrice {

    public static final String CYCLE_MONTHLY = "MONTHLY";
    public static final String CYCLE_YEARLY = "YEARLY";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "razorpay_plan_id", length = 50)
    private String razorpayPlanId;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "gst_rate", precision = 5, scale = 2)
    private BigDecimal gstRate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getRazorpayPlanId() { return razorpayPlanId; }
    public void setRazorpayPlanId(String razorpayPlanId) { this.razorpayPlanId = razorpayPlanId; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public BigDecimal getGstRate() { return gstRate; }
    public void setGstRate(BigDecimal gstRate) { this.gstRate = gstRate; }
}
