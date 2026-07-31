package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared fields for entities that are soft-deleted and optimistically locked:
 * Transaction, Account, Budget, Goal. Not applied to every entity in the codebase —
 * Category, MerchantCategoryMap, PasswordResetToken, NetWorthSnapshot, GoalContribution,
 * AuditLog, and User each have their own field sets that don't uniformly need all of
 * createdAt/updatedAt/deletedAt/version, so forcing them onto this base would mean adding
 * columns those tables don't actually need.
 */
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    public UUID getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getVersion() { return version; }
}
