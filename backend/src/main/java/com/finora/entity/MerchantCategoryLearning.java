package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * One row per (merchant, category) pair — a merchant's categorization is a distribution across
 * however many categories it's actually been confirmed under (e.g. Amazon: Shopping 71%,
 * Electronics 18%, Books 11%), not a single mutable "current category." confidence is that
 * pair's share of the merchant's total confirmations, recomputed by MerchantLearningService
 * whenever any pair's confirmation_count changes — it's cached here for query performance, but
 * the confirmation_count is the actual evidence; confidence is always derived from it.
 */
@Entity
@Table(name = "merchant_category_learning")
public class MerchantCategoryLearning {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    @Column(name = "confirmation_count", nullable = false)
    private int confirmationCount = 1;

    @Column(nullable = false)
    private int confidence = 100;

    @Column(name = "last_confirmed_at", nullable = false)
    private Instant lastConfirmedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public int getConfirmationCount() { return confirmationCount; }
    public void setConfirmationCount(int confirmationCount) { this.confirmationCount = confirmationCount; }
    public int getConfidence() { return confidence; }
    public void setConfidence(int confidence) { this.confidence = confidence; }
    public Instant getLastConfirmedAt() { return lastConfirmedAt; }
    public void setLastConfirmedAt(Instant lastConfirmedAt) { this.lastConfirmedAt = lastConfirmedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
