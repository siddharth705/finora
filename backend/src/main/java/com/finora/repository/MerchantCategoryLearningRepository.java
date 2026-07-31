package com.finora.repository;

import com.finora.entity.MerchantCategoryLearning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCategoryLearningRepository extends JpaRepository<MerchantCategoryLearning, UUID> {
    /** The full distribution across every category this merchant has ever been confirmed under. */
    List<MerchantCategoryLearning> findByUserIdAndMerchantId(UUID userId, UUID merchantId);

    Optional<MerchantCategoryLearning> findByUserIdAndMerchantIdAndCategoryId(UUID userId, UUID merchantId, UUID categoryId);

    List<MerchantCategoryLearning> findByUserId(UUID userId);
}
