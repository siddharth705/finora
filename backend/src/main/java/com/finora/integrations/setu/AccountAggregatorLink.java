package com.finora.integrations.setu;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One user's link to one FI (bank/card) account through Setu's Account Aggregator API. Mirrors
 * {@code GmailConnection}'s shape deliberately -- connection lifecycle only, nothing here syncs or
 * ingests a single transaction (that's Plan 2's AccountAggregatorTransactionMapper).
 *
 * <p>Not extending {@code BaseEntity}: same reasoning as {@code GmailConnection} and
 * {@code ImportSession} -- this is connection/session state, not the soft-deleted, optimistically
 * locked financial data BaseEntity's four other users (Account, Transaction, Budget, Goal) are.
 */
@Entity
@Table(name = "account_aggregator_links")
public class AccountAggregatorLink {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Null until identity resolution attaches this link to an Account (see
     *  AccountAggregatorIdentityResolutionService) -- which may not happen in the same request as
     *  consent approval, if the match is only PROBABLE and needs the user's own confirmation. */
    @Column(name = "account_id")
    private UUID accountId;

    /** Setu's identifier for this consent. Null only for a LINK_FAILED row whose gateway call
     *  failed before Setu ever returned one. */
    @Column(name = "consent_handle_id")
    private String consentHandleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fi_type", nullable = false, length = 20)
    private FiType fiType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AccountAggregatorLinkStatus status = AccountAggregatorLinkStatus.CONSENT_PENDING;

    @Column(name = "consent_expires_at")
    private Instant consentExpiresAt;

    /** Reserved for Plan 2 (transaction sync) -- always null until then. */
    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    /** Client-minted, unique per (user, attempt) -- see the design spec's "Link idempotency"
     *  section. Enforced by V197's unique index, not a select-then-insert check, same discipline
     *  {@code ReimportConfirmationClaim} already uses for the identical class of problem. */
    @Column(name = "link_idempotency_key", nullable = false)
    private String linkIdempotencyKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; touch(); }
    public String getConsentHandleId() { return consentHandleId; }
    public void setConsentHandleId(String consentHandleId) { this.consentHandleId = consentHandleId; touch(); }
    public FiType getFiType() { return fiType; }
    public void setFiType(FiType fiType) { this.fiType = fiType; }
    public AccountAggregatorLinkStatus getStatus() { return status; }
    public void setStatus(AccountAggregatorLinkStatus status) { this.status = status; touch(); }
    public Instant getConsentExpiresAt() { return consentExpiresAt; }
    public void setConsentExpiresAt(Instant consentExpiresAt) { this.consentExpiresAt = consentExpiresAt; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant lastSyncedAt) { this.lastSyncedAt = lastSyncedAt; touch(); }
    public String getLinkIdempotencyKey() { return linkIdempotencyKey; }
    public void setLinkIdempotencyKey(String linkIdempotencyKey) { this.linkIdempotencyKey = linkIdempotencyKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
