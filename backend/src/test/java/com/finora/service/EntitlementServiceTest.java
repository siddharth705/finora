package com.finora.service;

import com.finora.dto.BillingDtos.EntitlementsDto;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D-28 PR4-A. Covers the fail-CLOSED contract (Correction #3 of the billing proposal) -- every
 * missing-data path (no subscription, no plan row, no entitlement row) must resolve to "no
 * access", the opposite default from FeatureFlagRepository.isEnabled's fail-open convention.
 */
class EntitlementServiceTest {

    private SubscriptionRepository subscriptionRepository;
    private FeatureEntitlementRepository featureEntitlementRepository;
    private PlanRepository planRepository;
    private EntitlementService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID planId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        featureEntitlementRepository = mock(FeatureEntitlementRepository.class);
        planRepository = mock(PlanRepository.class);
        service = new EntitlementService(subscriptionRepository, featureEntitlementRepository, planRepository);
    }

    private Subscription activeSubscription() {
        Subscription s = new Subscription();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setUserId(userId);
        s.setPlanId(planId);
        s.setStatus(Subscription.STATUS_ACTIVE);
        return s;
    }

    private FeatureEntitlement entitlement(String key, boolean enabled) {
        FeatureEntitlement e = new FeatureEntitlement();
        e.setPlanId(planId);
        e.setFeatureKey(key);
        e.setEnabled(enabled);
        return e;
    }

    @Test
    void hasEntitlement_returnsFalse_whenUserHasNoActiveOrTrialSubscription() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.ADVANCED_REPORTS)).isFalse();
    }

    @Test
    void hasEntitlement_returnsFalse_whenNoEntitlementRowMatchesTheFeatureKey() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(activeSubscription()));
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(planId, FeatureEntitlement.FINO_AI))
                .thenReturn(Optional.empty());

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.FINO_AI)).isFalse();
    }

    @Test
    void hasEntitlement_returnsFalse_whenTheMatchingRowIsExplicitlyDisabled() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(activeSubscription()));
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(planId, FeatureEntitlement.PRIORITY_SUPPORT))
                .thenReturn(Optional.of(entitlement(FeatureEntitlement.PRIORITY_SUPPORT, false)));

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.PRIORITY_SUPPORT)).isFalse();
    }

    @Test
    void hasEntitlement_returnsTrue_whenTheMatchingRowIsEnabled() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(activeSubscription()));
        when(featureEntitlementRepository.findByPlanIdAndFeatureKey(planId, FeatureEntitlement.BASIC_DASHBOARD))
                .thenReturn(Optional.of(entitlement(FeatureEntitlement.BASIC_DASHBOARD, true)));

        assertThat(service.hasEntitlement(userId, FeatureEntitlement.BASIC_DASHBOARD)).isTrue();
    }

    @Test
    void entitlementsFor_returnsNullPlanAndEmptyMap_whenUserHasNoActiveOrTrialSubscription() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.empty());

        EntitlementsDto dto = service.entitlementsFor(userId);

        assertThat(dto.planCode()).isNull();
        assertThat(dto.planName()).isNull();
        assertThat(dto.features()).isEmpty();
    }

    @Test
    void entitlementsFor_returnsThePlanAndEveryFeatureRowSeededForIt() {
        when(subscriptionRepository.findActiveOrTrial(userId)).thenReturn(Optional.of(activeSubscription()));
        Plan plan = new Plan();
        ReflectionTestUtils.setField(plan, "id", planId);
        plan.setCode("PREMIUM");
        plan.setName("Premium");
        when(planRepository.findById(planId)).thenReturn(Optional.of(plan));
        when(featureEntitlementRepository.findByPlanId(planId)).thenReturn(List.of(
                entitlement(FeatureEntitlement.BASIC_DASHBOARD, true),
                entitlement(FeatureEntitlement.FINO_AI, true),
                entitlement(FeatureEntitlement.PRIORITY_SUPPORT, false)
        ));

        EntitlementsDto dto = service.entitlementsFor(userId);

        assertThat(dto.planCode()).isEqualTo("PREMIUM");
        assertThat(dto.planName()).isEqualTo("Premium");
        assertThat(dto.features()).containsEntry(FeatureEntitlement.BASIC_DASHBOARD, true);
        assertThat(dto.features()).containsEntry(FeatureEntitlement.FINO_AI, true);
        assertThat(dto.features()).containsEntry(FeatureEntitlement.PRIORITY_SUPPORT, false);
    }
}
