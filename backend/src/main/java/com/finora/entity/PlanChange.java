package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * D-28 PR4-A. Append-only upgrade/downgrade history (proposal §3.1b). {@link #effectiveAt} is
 * deliberately separate from {@link #createdAt} -- a change can be recorded now but take effect at
 * the next renewal date; timing itself is a product decision this schema supports without
 * presupposing (D-28/§10, still open). Same write-once shape as {@link SubscriptionEvent}.
 */
@Entity
@Table(name = "plan_changes")
public class PlanChange {

    public static final String REASON_USER_INITIATED = "USER_INITIATED";
    public static final String REASON_PAYMENT_FAILURE_DOWNGRADE = "PAYMENT_FAILURE_DOWNGRADE";
    public static final String REASON_ADMIN_OVERRIDE = "ADMIN_OVERRIDE";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "subscription_id", nullable = false)
    private UUID subscriptionId;

    @Column(name = "from_plan_id")
    private UUID fromPlanId;

    @Column(name = "to_plan_id", nullable = false)
    private UUID toPlanId;

    @Column(name = "effective_at", nullable = false)
    private Instant effectiveAt;

    @Column(nullable = false, length = 30)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public UUID getFromPlanId() { return fromPlanId; }
    public void setFromPlanId(UUID fromPlanId) { this.fromPlanId = fromPlanId; }
    public UUID getToPlanId() { return toPlanId; }
    public void setToPlanId(UUID toPlanId) { this.toPlanId = toPlanId; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant effectiveAt) { this.effectiveAt = effectiveAt; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
}
