package com.finora.repository;

import com.finora.entity.MerchantCategoryLearning;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
