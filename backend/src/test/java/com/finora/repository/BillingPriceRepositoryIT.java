package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPriceRepositoryIT extends AbstractIntegrationTest {

    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private PlanRepository planRepository;

    @Test
    void findsTheActivePriceForAPlanAndCycle() {
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isPresent();
        assertThat(monthly.get().getPrice()).isEqualByComparingTo(new BigDecimal("799.00"));
    }

    @Test
    void freePlanHasNoBillingPriceRow() {
        Plan free = planRepository.findByCode("FREE").orElseThrow();

        Optional<BillingPrice> monthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(free.getId(), BillingPrice.CYCLE_MONTHLY);

        assertThat(monthly).isEmpty();
    }
}
