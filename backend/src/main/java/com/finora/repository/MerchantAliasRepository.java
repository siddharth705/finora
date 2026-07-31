package com.finora.repository;

import com.finora.entity.MerchantAlias;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantAliasRepository extends JpaRepository<MerchantAlias, UUID> {
    Optional<MerchantAlias> findByUserIdAndNormalizedAlias(UUID userId, String normalizedAlias);
    List<MerchantAlias> findByMerchantId(UUID merchantId);
}
