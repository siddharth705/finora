package com.finora.repository;

import com.finora.entity.MerchantLearningAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MerchantLearningAuditRepository extends JpaRepository<MerchantLearningAudit, UUID> {
    // Scoped by BOTH userId and merchantId -- a query keyed only on merchantId would let
    // MerchantLearningService.undo() read (and act on) another user's real audit history,
    // including their previousCategoryId, given nothing more than a guessed/enumerated
    // merchantId. Currently unreachable from any controller (see MerchantLearningService's own
    // doc comment: not wired in until Milestone B), but fixed proactively -- an unscoped query
    // like this is a landmine for whoever wires it up next, not a "safe because unused today"
    // situation.
    //
    // Self-review catch (Phase 4c mediums batch, while investigating Bug 54): this used to be a
    // name-derived query with no tiebreaker on createdAt -- exactly the failure mode
    // findByUserIdOrderByCreatedAtAscIdAsc's own comment below already documents and fixes for
    // the OTHER audit query in this interface. MerchantLearningAudit.createdAt is a plain
    // `Instant.now()` field initializer, not a DB sequence, so two rows written back-to-back (a
    // worker or an admin bulk action confirming several categories for the same merchant in a
    // tight loop) can land in the same clock tick. undo() reads exactly this list's FIRST element
    // as "the most recent action" and reverts whatever category it named -- with no tiebreaker, a
    // tie between two DIFFERENT categories' entries left which one "most recent" meant
    // unspecified, so undo() could revert the wrong one. Converted from a name-derived query to
    // an explicit @Query specifically so the tiebreaker could be added without renaming a method
    // called from MerchantService.auditHistory/merge, MerchantLearningService.undo, and several
    // tests. Same caveat as the sibling query: id (a random UUID) doesn't reconstruct true
    // insertion order for a genuine tie -- it only guarantees a deterministic answer, which is
    // all either query can promise once the wall clock itself cannot tell two rows apart.
    @Query("SELECT a FROM MerchantLearningAudit a WHERE a.userId = :userId AND a.merchantId = :merchantId "
            + "ORDER BY a.createdAt DESC, a.id DESC")
    List<MerchantLearningAudit> findByUserIdAndMerchantIdOrderByCreatedAtDesc(
            @Param("userId") UUID userId, @Param("merchantId") UUID merchantId);

    // Cross-merchant, unlike the query above -- backs AnalyticsService.learningGrowth() (grouped
    // by month in-memory) and the Workspace's future Learning Engine timeline (task #69). Still
    // userId-scoped, same reasoning as the comment above: this is a real audit trail, never
    // queryable by anything less specific than the owning user.
    //
    // Ordered explicitly because MerchantLearningService.timeline() breaks createdAt ties by
    // relying on a stable sort preserving "DB/insertion order, i.e. oldest-among-ties first" --
    // a property no ORDER BY-less query provides. These rows are append-only so heap order tracks
    // insertion far more reliably here than it does for merchant_category_learning, but "more
    // reliably" is not "specified". id is the tie-break within one clock tick.
    List<MerchantLearningAudit> findByUserIdOrderByCreatedAtAscIdAsc(UUID userId);

    default List<MerchantLearningAudit> findByUserId(UUID userId) {
        return findByUserIdOrderByCreatedAtAscIdAsc(userId);
    }

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);

    // Admin Portal, Learning Engine module -- platform-wide action counts for the aggregate
    // stats tile. Cheap grouped COUNT, same "simple indexed counts, not a new reporting
    // subsystem" discipline as AdminStatsService. See AdminLearningStatsService for how the
    // rows (Action enum, count) get turned into the DTO's named fields.
    @Query("SELECT a.action, COUNT(a) FROM MerchantLearningAudit a GROUP BY a.action")
    List<Object[]> platformActionCounts();
}
