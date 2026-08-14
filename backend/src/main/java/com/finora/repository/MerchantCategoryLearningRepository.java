package com.finora.repository;

import com.finora.entity.MerchantCategoryLearning;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCategoryLearningRepository extends JpaRepository<MerchantCategoryLearning, UUID> {
    /**
     * The full distribution across every category this merchant has ever been confirmed under,
     * in a defined order.
     *
     * <p>The {@code OrderBy} is load-bearing, not cosmetic. {@code ConfidenceEngine.topCategory}
     * decides which category is auto-applied to a merchant, and it resolves a tie on both
     * confirmationCount AND lastConfirmedAt by taking the last element of a STABLE sort -- i.e.
     * by the order this query returned rows in. Its comment justified that as "confirmation/
     * insertion order both here and for a plain findByUserIdAndMerchantId query with no ORDER
     * BY", which a SQL result set does not promise. PostgreSQL commonly returns heap order, and
     * heap order specifically does not survive updates: an UPDATE writes a new tuple version,
     * typically elsewhere in the page or appended. {@code MerchantLearningService.confirm} calls
     * {@code recomputeAndSave} over every row for the merchant on EVERY confirmation, so the rows
     * whose relative order was being trusted as insertion order are rewritten constantly. The
     * auto-applied category could therefore change between two identical reads with no underlying
     * data change -- the exact "silently depended on DB retrieval order rather than any real
     * signal" behaviour that fix set out to remove.
     *
     * <p>createdAt then id: createdAt is the intended meaning (oldest confirmation first), and id
     * breaks the remaining tie when two rows were created in the same clock tick -- the same
     * coarse-clock case that made lastConfirmedAt insufficient in the first place.
     */
    List<MerchantCategoryLearning> findByUserIdAndMerchantIdOrderByCreatedAtAscIdAsc(UUID userId, UUID merchantId);

    default List<MerchantCategoryLearning> findByUserIdAndMerchantId(UUID userId, UUID merchantId) {
        return findByUserIdAndMerchantIdOrderByCreatedAtAscIdAsc(userId, merchantId);
    }

    Optional<MerchantCategoryLearning> findByUserIdAndMerchantIdAndCategoryId(UUID userId, UUID merchantId, UUID categoryId);

    List<MerchantCategoryLearning> findByUserId(UUID userId);

    /**
     * BH-053. Atomic upsert-or-noop: guarantees the (user, merchant, category) row exists,
     * inserting it with {@code confirmation_count = 0} if it doesn't, leaving an existing row
     * completely untouched if it does. Deliberately stays in the CALLER's transaction, unlike
     * {@link RegisteredLayoutRepository#observe}'s superficially similar upsert -- see
     * {@code MerchantLearningService.confirm()}'s own doc comment for why {@code REQUIRES_NEW}
     * cannot be used here: this row's foreign keys routinely point at parent rows the caller's
     * own, still-uncommitted transaction just created, which a suspended-and-restarted inner
     * transaction cannot see.
     *
     * <p>This is what closes the documented check-then-act race. {@code confirm()} calls this
     * BEFORE reading anything, so by the time it reads and later saves via {@code saveAll}, the
     * row it might otherwise have tried to INSERT is already guaranteed to exist -- no code path
     * downstream can attempt an INSERT that could violate
     * {@code UNIQUE(user_id, merchant_id, category_id)}. Two concurrent callers racing on the
     * same brand-new pair both run this statement; the database resolves the conflict atomically
     * and silently, and both callers' subsequent reads see the one row that resulted, never a
     * constraint violation.
     *
     * <p>{@code confirmation_count} starts at 0, not the column's own {@code DEFAULT 1} -- see
     * V7's migration -- because {@code confirm()} unconditionally increments by 1 immediately
     * after this call, uniformly for a brand-new pair and one that already existed, so a
     * genuinely new pair still ends at 1, matching this method's pre-fix behavior exactly.
     */
    @Modifying
    @Query(value = """
           INSERT INTO merchant_category_learning
               (id, user_id, merchant_id, category_id, confirmation_count, confidence, last_confirmed_at, created_at, updated_at)
           VALUES
               (gen_random_uuid(), :userId, :merchantId, :categoryId, 0, 0, now(), now(), now())
           ON CONFLICT (user_id, merchant_id, category_id) DO NOTHING
           """, nativeQuery = true)
    void ensurePairExists(@Param("userId") UUID userId, @Param("merchantId") UUID merchantId, @Param("categoryId") UUID categoryId);
}
