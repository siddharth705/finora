package com.finora.service;

import com.finora.dto.BillingDtos.EntitlementsDto;
import com.finora.entity.FeatureEntitlement;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.repository.FeatureEntitlementRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * D-28 PR4-A. The fail-CLOSED entitlement lookup (proposal §3.2, Correction #3) -- deliberately a
 * different code path from {@code FeatureFlagRepository.isEnabled} (fail-open), not a
 * parameterized variant of it, since the two have opposite failure-mode requirements: an unknown
 * platform toggle should default to "on" (nothing breaks), an unknown paid feature must default to
 * "off" (a mistyped key must never become a revenue leak).
 *
 * No caching -- a live query per check, matching {@code PlatformSettingsService}'s own "no cache"
 * reasoning: an admin's manual plan change should take effect on the next request, not wait out a
 * TTL. Revisit only with evidence of actual load (proposal §3.2's own note), not preemptively.
 */
@Service
public class EntitlementService {

    private final SubscriptionRepository subscriptionRepository;
    private final FeatureEntitlementRepository featureEntitlementRepository;
    private final PlanRepository planRepository;

    public EntitlementService(SubscriptionRepository subscriptionRepository,
                               FeatureEntitlementRepository featureEntitlementRepository,
                               PlanRepository planRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.featureEntitlementRepository = featureEntitlementRepository;
        this.planRepository = planRepository;
    }

    /** @return false if the user has no active/trial subscription, the plan has no row for this
     *  feature key, or the row is explicitly disabled -- every failure mode resolves to "no
     *  access", never "everyone gets it free." */
    @Transactional(readOnly = true)
    public boolean hasEntitlement(UUID userId, String featureKey) {
        return subscriptionRepository.findActiveOrTrial(userId)
                .flatMap(sub -> featureEntitlementRepository.findByPlanIdAndFeatureKey(sub.getPlanId(), featureKey))
                .map(FeatureEntitlement::isEnabled)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public EntitlementsDto entitlementsFor(UUID userId) {
        Subscription subscription = subscriptionRepository.findActiveOrTrial(userId).orElse(null);
        if (subscription == null) {
            return new EntitlementsDto(null, null, Map.of());
        }
        Plan plan = planRepository.findById(subscription.getPlanId()).orElse(null);
        Map<String, Boolean> features = featureEntitlementRepository.findByPlanId(subscription.getPlanId()).stream()
                .collect(Collectors.toMap(FeatureEntitlement::getFeatureKey, FeatureEntitlement::isEnabled));
        return new EntitlementsDto(plan != null ? plan.getCode() : null, plan != null ? plan.getName() : null, features);
    }
}
