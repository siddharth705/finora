package com.finora.service;

import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscription billing V4 (design spec §5). One method per RevenueCat event type this application
 * acts on, mirroring RazorpayWebhookDispatcher's own shape and self-invocation caveat
 * (@Transactional lives on dispatch(), not the individual handlers, for the identical reason --
 * see that class's own doc comment).
 */
@Component
public class RevenueCatWebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookDispatcher.class);

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final IapProductRepository iapProductRepository;

    public RevenueCatWebhookDispatcher(SubscriptionRepository subscriptionRepository, PlanRepository planRepository,
                                        IapProductRepository iapProductRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.planRepository = planRepository;
        this.iapProductRepository = iapProductRepository;
    }

    @Transactional
    public void dispatch(String eventType, Map<String, Object> eventPayload) {
        switch (eventType) {
            case "INITIAL_PURCHASE" -> handleInitialPurchase(eventPayload);
            case "RENEWAL" -> handleRenewal(eventPayload);
            default -> log.info("RevenueCat webhook event '{}' received but not handled in V4 yet.", eventType);
        }
    }

    private Optional<Subscription> subscriptionForAppUserId(Map<String, Object> eventPayload) {
        String appUserId = (String) eventPayload.get("app_user_id");
        if (appUserId == null) return Optional.empty();
        try {
            return subscriptionRepository.findActiveOrTrial(UUID.fromString(appUserId));
        } catch (IllegalArgumentException e) {
            log.warn("RevenueCat webhook app_user_id '{}' is not a valid Fynora user id, ignoring.", appUserId);
            return Optional.empty();
        }
    }

    /** spec §6.1 step 4 / §5. app_user_id is always the real Fynora user id (spec §2's "purchase
     *  requires authentication" decision) -- never RevenueCat's own anonymous id -- so this is a
     *  direct lookup, no mapping table. */
    void handleInitialPurchase(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;

        String productId = (String) eventPayload.get("product_id");
        String store = (String) eventPayload.get("store");
        String platform = "PLAY_STORE".equals(store) ? "ANDROID" : "IOS";
        IapProduct product = iapProductRepository.findByProviderProductIdAndPlatform(productId, platform).orElse(null);
        if (product == null) {
            log.warn("RevenueCat product_id '{}' ({}) has no iap_products mapping, ignoring.", productId, platform);
            return;
        }
        Plan plan = planRepository.findById(product.getPlanId()).orElseThrow();

        subscription.setPlanId(plan.getId());
        subscription.setBillingCycle(product.getBillingCycle());
        subscription.setPaymentProvider("REVENUECAT");
        subscription.setStorePlatform(platform);
        subscription.setRevenuecatOriginalTransactionId((String) eventPayload.get("original_transaction_id"));
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        subscription.setAutoRenew(true);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);
    }

    /** spec §5. Renewal is passive -- just refresh the expiration date. */
    void handleRenewal(Map<String, Object> eventPayload) {
        Subscription subscription = subscriptionForAppUserId(eventPayload).orElse(null);
        if (subscription == null) return;
        subscription.setStatus(Subscription.STATUS_ACTIVE);
        applyExpiration(subscription, eventPayload);
        subscriptionRepository.save(subscription);
    }

    private void applyExpiration(Subscription subscription, Map<String, Object> eventPayload) {
        Object expirationAtMs = eventPayload.get("expiration_at_ms");
        if (expirationAtMs instanceof Number n) {
            subscription.setRenewalDate(LocalDate.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneOffset.UTC));
        }
    }
}
