package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One edge in the transaction graph (docs/proposals/reconciliation-evolution-roadmap-proposal.md,
 * Part 3) -- a many-to-many alternative to {@link Transaction}'s single-pointer legacy columns
 * ({@code isDuplicateOf}/{@code transferPairId}/{@code refundOfTransactionId}), which stay in
 * place and are dual-written alongside this table rather than replaced. A relationship a legacy
 * column cannot express -- a credit card payment settling many spends -- needs many rows here, one
 * edge each; the legacy columns can hold at most one.
 *
 * <p>Not extending {@link BaseEntity}: this table is never soft-deleted (a superseded edge stays,
 * see {@link #supersededBy}) and never optimistically locked (each write is a fresh row, not a
 * contested update to an existing one), so {@code deletedAt}/{@code version} would be dead columns
 * on every row.
 */
@Entity
@Table(name = "transaction_relationships")
public class TransactionRelationship {

    public enum RelationshipType {
        TRANSFER, REFUND, REVERSAL, DUPLICATE,
        CC_PAYMENT, EMI, SALARY, LOAN_REPAYMENT, INVESTMENT_TRANSFER, CASH_WITHDRAWAL, CASH_DEPOSIT
    }

    public enum Status { CANDIDATE, AUTO_CONFIRMED, USER_CONFIRMED, REJECTED }

    public enum DetectionMethod { RULE_ENGINE, MANUAL, AA_FEED, USER_OVERRIDE }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "from_transaction_id", nullable = false)
    private UUID fromTransactionId;

    @Column(name = "to_transaction_id", nullable = false)
    private UUID toTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false)
    private RelationshipType relationshipType;

    @Column(name = "matched_amount")
    private BigDecimal matchedAmount;

    @Column(name = "confidence")
    private Integer confidence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CANDIDATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "detection_method", nullable = false)
    private DetectionMethod detectionMethod;

    // Same jsonb-Map convention as Transaction.reconciliationExplanation and AuditLog.metadata --
    // see that field's own comment for why this is stored as discrete keys rather than a rendered
    // sentence.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> explanation;

    // Points at the edge that replaced this one -- e.g. a RULE_ENGINE candidate a user later
    // rejected in favor of a manually-created one. Null for every edge that is still the live
    // answer for its (from, to, type) triple. Never populated by this Phase 2 slice (no caller
    // supersedes anything yet); present now so a future USER_OVERRIDE write needs no migration.
    @Column(name = "superseded_by")
    private UUID supersededBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getFromTransactionId() { return fromTransactionId; }
    public void setFromTransactionId(UUID fromTransactionId) { this.fromTransactionId = fromTransactionId; }
    public UUID getToTransactionId() { return toTransactionId; }
    public void setToTransactionId(UUID toTransactionId) { this.toTransactionId = toTransactionId; }
    public RelationshipType getRelationshipType() { return relationshipType; }
    public void setRelationshipType(RelationshipType relationshipType) { this.relationshipType = relationshipType; }
    public BigDecimal getMatchedAmount() { return matchedAmount; }
    public void setMatchedAmount(BigDecimal matchedAmount) { this.matchedAmount = matchedAmount; }
    public Integer getConfidence() { return confidence; }
    public void setConfidence(Integer confidence) { this.confidence = confidence; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public DetectionMethod getDetectionMethod() { return detectionMethod; }
    public void setDetectionMethod(DetectionMethod detectionMethod) { this.detectionMethod = detectionMethod; }
    public Map<String, Object> getExplanation() { return explanation; }
    public void setExplanation(Map<String, Object> explanation) { this.explanation = explanation; }
    public UUID getSupersededBy() { return supersededBy; }
    public void setSupersededBy(UUID supersededBy) { this.supersededBy = supersededBy; }
    public Instant getCreatedAt() { return createdAt; }
}
