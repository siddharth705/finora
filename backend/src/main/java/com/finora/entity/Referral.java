package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * D-28 PR4-C. One referral (proposal §4) -- {@code referred_user_id} is unique, so a user is
 * referred at most once, ever. No {@link #STATUS_INVITED} row is ever created by this codebase
 * (Finora has no invite-by-email mechanism); every row starts at {@link #STATUS_REGISTERED}, the
 * moment someone signs up with a valid code (see {@code ReferralService.redeemCode}). Not
 * extending {@link BaseEntity}: this is a status-tracking row updated in place by well-defined,
 * one-directional transitions (REGISTERED -> SUBSCRIBED -> REWARDED), not a soft-deletable
 * user-owned resource.
 */
@Entity
@Table(name = "referrals")
public class Referral {

    public static final String STATUS_INVITED = "INVITED";
    public static final String STATUS_REGISTERED = "REGISTERED";
    public static final String STATUS_SUBSCRIBED = "SUBSCRIBED";
    public static final String STATUS_REWARDED = "REWARDED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;

    @Column(name = "referred_user_id", nullable = false, unique = true)
    private UUID referredUserId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column
    private BigDecimal reward;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getReferrerUserId() { return referrerUserId; }
    public void setReferrerUserId(UUID referrerUserId) { this.referrerUserId = referrerUserId; }
    public UUID getReferredUserId() { return referredUserId; }
    public void setReferredUserId(UUID referredUserId) { this.referredUserId = referredUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getReward() { return reward; }
    public void setReward(BigDecimal reward) { this.reward = reward; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
