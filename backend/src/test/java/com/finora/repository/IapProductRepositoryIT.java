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

        assertThat(iapProductRepository.findByProviderProductIdAndPlatformAndActiveTrue("plus_monthly", "IOS"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("IOS");
        assertThat(iapProductRepository.findByProviderProductIdAndPlatformAndActiveTrue("plus_monthly", "ANDROID"))
                .isPresent().get().extracting(IapProduct::getPlatform).isEqualTo("ANDROID");
    }

    /** A retired product mapping (e.g. a discontinued App Store product id) must stop resolving --
     *  mirrors billingPriceRepository.findByPlanIdAndBillingCycleAndActiveTrue's own active-flag
     *  discipline, which this table's `active` column was added to mirror in the first place. */
    @Test
    void anInactiveMappingIsNotResolved() {
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();

        IapProduct retired = new IapProduct();
        retired.setProviderProductId("plus_monthly_retired_" + java.util.UUID.randomUUID());
        retired.setPlanId(plus.getId());
        retired.setBillingCycle("MONTHLY");
        retired.setPlatform("IOS");
        retired.setActive(false);
        retired = iapProductRepository.save(retired);

        assertThat(iapProductRepository.findByProviderProductIdAndPlatformAndActiveTrue(
                retired.getProviderProductId(), "IOS")).isEmpty();
    }
}
