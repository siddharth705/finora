package com.finora.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchant_learning_audit")
public class MerchantLearningAudit {

    // RESET: Financial Intelligence Workspace, Learning Engine module -- "Reset Learning" wipes a
    // merchant's ENTIRE distribution in one action (MerchantLearningService.reset()), unlike
    // UNDONE, which only reverts the single most recent confirmation. Kept as its own action
    // rather than reusing UNDONE so the timeline can tell "stepped back one correction" apart
    // from "started this merchant's learning over from nothing."
    public enum Action { LEARNED, CORRECTED, UNDONE, MERGED, RESET }

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Action action;

    @Column(name = "previous_category_id")
    private UUID previousCategoryId;

    @Column(name = "new_category_id")
    private UUID newCategoryId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public Action getAction() { return action; }
    public void setAction(Action action) { this.action = action; }
    public UUID getPreviousCategoryId() { return previousCategoryId; }
    public void setPreviousCategoryId(UUID previousCategoryId) { this.previousCategoryId = previousCategoryId; }
    public UUID getNewCategoryId() { return newCategoryId; }
    public void setNewCategoryId(UUID newCategoryId) { this.newCategoryId = newCategoryId; }
    public Instant getCreatedAt() { return createdAt; }
}
