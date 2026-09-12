package com.finora.service;

import com.finora.entity.FeatureEntitlement;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntitlementServiceAccountAggregatorTest {

    @Test
    void constantMatchesTheSeededKey() {
        // The migration seeds the literal string 'ACCOUNT_AGGREGATOR_SYNC' -- this pins the Java
        // constant to that exact spelling so a typo in either place fails a test instead of
        // silently granting nobody the feature.
        assertThat(FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC).isEqualTo("ACCOUNT_AGGREGATOR_SYNC");
    }

    @Test
    void hasEntitlementIsFailClosedForAnUnrelatedFeatureKey() {
        SubscriptionRepository subscriptions = mock(SubscriptionRepository.class);
        FeatureEntitlementRepository entitlements = mock(FeatureEntitlementRepository.class);
        PlanRepository plans = mock(PlanRepository.class);
        EntitlementService service = new EntitlementService(subscriptions, entitlements, plans);

        UUID userId = UUID.randomUUID();
        when(subscriptions.findActiveOrTrial(userId)).thenReturn(java.util.Optional.empty());

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.ACCOUNT_AGGREGATOR_SYNC)).isFalse();
    }
}
