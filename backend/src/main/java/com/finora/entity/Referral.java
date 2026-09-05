package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * MVP referral relationship (see the Refer &amp; Earn scope cut) -- {@code referred_user_id} is
 * unique, so a user is referred at most once, ever. The only thing this row records is "referrer
 * X brought in referred user Y" -- no reward or status lifecycle. {@code status} stays mapped
 * (and fixed at REGISTERED) because the underlying V101 column is NOT NULL; {@code reward} is
 * left unmapped -- it's nullable in the DB (V101) and nothing in this codebase writes or reads
 * it. Not extending {@link BaseEntity}: a plain, never-edited join row, not a soft-deletable
 * user-owned resource.
 */
@Entity
@Table(name = "referrals")
public class Referral {

    /** The only status this codebase ever writes -- see this class's own doc comment. */
    public static final String STATUS_REGISTERED = "REGISTERED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "referrer_user_id", nullable = false)
    private UUID referrerUserId;

    @Column(name = "referred_user_id", nullable = false, unique = true)
    private UUID referredUserId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_REGISTERED;

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
    public Instant getCreatedAt() { return createdAt; }
}
