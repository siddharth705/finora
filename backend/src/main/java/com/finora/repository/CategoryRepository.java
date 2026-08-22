package com.finora.repository;

import com.finora.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByUserId(UUID userId);
    Optional<Category> findByUserIdAndName(UUID userId, String name);

    /**
     * Bug 16 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). {@code findByUserIdAndName} is an
     * exact, case-sensitive match against a case-sensitive {@code UNIQUE(user_id, name)} index --
     * nothing normalises case on the way in, so "dining" and "Dining" become two rows that split
     * a budget and double-count in reports. {@code CategorizationService.resolveOrCreateCategory}
     * and {@code BudgetService.upsert} use this instead, so a name that already exists under any
     * casing resolves to the SAME category rather than creating a sibling.
     *
     * <p>Does not close the underlying race: the unique index itself is still case-sensitive, so
     * two requests racing with genuinely different casing for a brand-new category name can both
     * pass this check and both insert. That would need a case-insensitive expression index, which
     * is a schema change out of scope for this fix -- this closes the reported, and by far the
     * more common, sequential-request case (a user or an import typing the same category two
     * different ways over time).
     *
     * <p>Self-review catch: returns a {@code List}, not {@code Optional}, and ordered, on
     * purpose. A single-result derived query throws {@code IncorrectResultSizeDataAccessException}
     * -- an unhandled 500 -- the moment it matches more than one row, and this exact bug's own
     * scenario (a user who already has both "Dining" and "dining" from BEFORE this fix shipped)
     * guarantees precisely that for every affected user, on every future category action, from
     * here on. Callers take the list's first element deterministically instead of crashing --
     * {@code id} is a random {@code UUID} ({@code @GeneratedValue}), so "first" carries no
     * chronological meaning, only repeatability (the same duplicate pair always resolves to the
     * same row rather than picking differently query to query). That gracefully degrades a
     * pre-existing duplicate into "pick one and keep working" rather than turning it into a hard
     * outage this fix would otherwise have introduced for exactly the users it was meant to help.
     */
    List<Category> findByUserIdAndNameIgnoreCaseOrderByIdAsc(UUID userId, String name);

    /** AccountPurgeSweepService -- called after every table that FK's to categories (transactions,
     *  budgets, merchant_category_learning, merchant_learning_events, merchant_learning_audit) is
     *  already empty. Hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
