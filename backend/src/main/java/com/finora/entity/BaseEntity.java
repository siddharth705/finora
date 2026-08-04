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
 *
 * <p><b>Always use the return value of {@code repository.save(...)} for these entities.</b> The
 * {@code @Version} field below is initialised to {@code 0L} rather than left null, so Spring Data's
 * {@code isNew()} check — which consults the version property before the id — decides a brand-new
 * instance is NOT new and calls {@code merge()} instead of {@code persist()}. {@code merge()}
 * returns a managed copy and leaves the instance you passed in with a null id:
 *
 * <pre>
 *   accountRepository.save(account);
 *   txn.setAccountId(account.getId());   // null -> NOT NULL violation on insert
 *
 *   account = accountRepository.save(account);
 *   txn.setAccountId(account.getId());   // correct
 * </pre>
 *
 * <p>Entities that do NOT extend this class (Merchant, Category, ...) have no version field, so
 * {@code isNew()} falls back to the null id, {@code persist()} runs, and the original instance does
 * get its id — which is exactly why the difference is easy to miss: the same code shape works for
 * some entities and silently fails for others. Four integration tests were written against the
 * wrong half of that and inserted rows with null foreign keys.
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
