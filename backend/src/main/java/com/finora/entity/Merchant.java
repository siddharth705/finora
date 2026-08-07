package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "canonical_name", nullable = false)
    private String canonicalName;

    @Column(name = "logo_url")
    private String logoUrl;

    private String website;

    /**
     * Whether a human has confirmed this merchant exists, or the engine merely guessed it.
     *
     * <p>TEMPORARY is what {@code MerchantNormalizationEngine} creates: a first-significant-token
     * guess from a description it had never seen. APPROVED is what a person confirms, and what
     * every merchant pre-dating V64 is backfilled to, since those all came from a confirmed import
     * or an explicit admin action.
     *
     * <p>The distinction is what makes Bug 36 addressable at all. Before it, a merchant invented
     * while staging a statement the user then abandoned was indistinguishable from one they had
     * actually transacted with — both counted in the Merchants page and in platform totals. It is
     * also the prerequisite for WI3: staging can only stop persisting once unknown merchants have
     * somewhere else to go.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false)
    private Lifecycle lifecycleStatus = Lifecycle.APPROVED;

    public enum Lifecycle {
        /** Created automatically by the engine; awaiting review. */
        TEMPORARY,
        /** An operator has picked it up and is deciding. */
        UNDER_REVIEW,
        /** Confirmed by a person, or pre-dating V64. */
        APPROVED
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Lifecycle getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(Lifecycle lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getCanonicalName() { return canonicalName; }
    public void setCanonicalName(String canonicalName) { this.canonicalName = canonicalName; }
    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
