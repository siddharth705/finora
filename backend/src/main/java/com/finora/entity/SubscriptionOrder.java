package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Subscription billing V1 (design spec §4.4). Business/audit layer, independent of {@code payments}
 * -- exists for funnel/support visibility (abandoned checkouts, upgrade-in-progress correlation),
 * never read by entitlements or {@code payments}. {@code razorpaySubscriptionId} is set at creation
 * time, before any webhook arrives -- it is how the webhook handler correlates an incoming
 * {@code subscription.activated} event back to the order that requested it, which matters most
 * during an upgrade, where the user's existing {@code subscriptions} row already points at a
 * different Razorpay subscription id.
 */
@Entity
@Table(name = "subscription_orders")
public class SubscriptionOrder {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ABANDONED = "ABANDONED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "billing_cycle", nullable = false, length = 10)
    private String billingCycle;

    @Column(name = "razorpay_subscription_id", length = 50)
    private String razorpaySubscriptionId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getRazorpaySubscriptionId() { return razorpaySubscriptionId; }
    public void setRazorpaySubscriptionId(String razorpaySubscriptionId) { this.razorpaySubscriptionId = razorpaySubscriptionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
