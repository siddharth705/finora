package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class IapProductRepositoryIT extends AbstractIntegrationTest {

    @Autowired private IapProductRepository iapProductRepository;
    @Autowired private PlanRepository planRepository;

    @Test
    void resolvesTheSameNominalProductIdOnTwoDifferentPlatformsAsTwoDistinctRows() {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();

        IapProduct ios = new IapProduct();
        ios.setProviderProductId("plus_monthly");
        ios.setPlanId(plus.getId());
        ios.setBillingCycle("MONTHLY");
        ios.setPlatform("IOS");
        iapProductRepository.save(ios);

        IapProduct android = new IapProduct();
        android.setProviderProductId("plus_monthly");
        android.setPlanId(plus.getId());
        android.setBillingCycle("MONTHLY");
        android.setPlatform("ANDROID");
        iapProductRepository.save(android);

        assertThat(iapProductRepository.findByProviderProductIdAndPlatform("plus_monthly", "IOS"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("IOS");
        assertThat(iapProductRepository.findByProviderProductIdAndPlatform("plus_monthly", "ANDROID"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("ANDROID");
    }
}
