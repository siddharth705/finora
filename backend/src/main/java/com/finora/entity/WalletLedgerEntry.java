package com.finora.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * D-28 PR4-C. One append-only wallet movement (proposal §4) -- a signed {@code amount}, never a
 * mutable balance column on {@link User}. A user's balance is a computed SUM over this table (see
 * {@code WalletLedgerRepository.sumAmountByUserId}), same "immutable financial log" posture as
 * {@code AuditLog} and PR4-B's own {@link Payment} -- not extending {@link BaseEntity}.
 */
@Entity
@Table(name = "wallet_ledger")
public class WalletLedgerEntry {

    public static final String REASON_REFERRAL_REWARD = "REFERRAL_REWARD";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Signed -- credit positive, debit negative. Every reason this codebase writes today is a
     *  credit; the sign exists so a future debit reason (e.g. redeeming wallet credit against an
     *  invoice) needs no schema change. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 30)
    private String reason;

    /** The referral (or other future event) that caused this entry -- {@link Referral#getId()} for
     *  every row written today. */
    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public UUID getReferenceId() { return referenceId; }
    public void setReferenceId(UUID referenceId) { this.referenceId = referenceId; }
    public Instant getCreatedAt() { return createdAt; }
}
