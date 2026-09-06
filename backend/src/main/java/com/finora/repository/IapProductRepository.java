package com.finora.repository;

import com.finora.entity.IapProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IapProductRepository extends JpaRepository<IapProduct, UUID> {
    Optional<IapProduct> findByProviderProductIdAndPlatform(String providerProductId, String platform);
}
