package com.finora.repository;

import com.finora.entity.MerchantCategoryMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantCategoryMapRepository extends JpaRepository<MerchantCategoryMap, UUID> {
    List<MerchantCategoryMap> findByUserId(UUID userId);
    Optional<MerchantCategoryMap> findByUserIdAndNormalizedDesc(UUID userId, String normalizedDesc);

    /** AccountPurgeSweepService -- hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
