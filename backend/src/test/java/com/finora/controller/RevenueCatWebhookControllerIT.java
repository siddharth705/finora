package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
import com.finora.entity.Subscription;
import com.finora.entity.User;
import com.finora.repository.IapProductRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueCatWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private IapProductRepository iapProductRepository;

    @Value("${app.integrations.revenuecat.webhook-signing-secret}")
    private String webhookSecret;

    private void postSigned(String body) {
        long now = Instant.now().getEpochSecond();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-RevenueCat-Webhook-Signature", TestHmac.header(body, now, webhookSecret));
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/revenuecat", new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aRealisticInitialPurchaseActivatesTheSubscriptionAtTheMappedPlan() {
        User user = new User();
        user.setEmail("revenuecat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        IapProduct product = new IapProduct();
        product.setProviderProductId("plus_monthly_it_" + UUID.randomUUID());
        product.setPlanId(plus.getId());
        product.setBillingCycle("MONTHLY");
        product.setPlatform("IOS");
        product = iapProductRepository.save(product);

        long expirationEpochMs = Instant.now().plusSeconds(2_592_000).toEpochMilli();
        String body = """
                {"event":{"type":"INITIAL_PURCHASE","app_user_id":"%s","product_id":"%s",
                 "store":"APP_STORE","original_transaction_id":"txn_it_1",
                 "expiration_at_ms":%d}}
                """.formatted(user.getId(), product.getProviderProductId(), expirationEpochMs);

        postSigned(body);

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(plus.getId());
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
        assertThat(subscription.getStorePlatform()).isEqualTo("IOS");
        assertThat(subscription.getRevenuecatOriginalTransactionId()).isEqualTo("txn_it_1");
        assertThat(subscription.isAutoRenew()).isTrue();
    }

    @Test
    void cancellationFlipsAutoRenewWithoutTouchingStatusOrPlan() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");

        postSigned(revenueCatBody("CANCELLATION", user.getId(), Map.of()));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.isAutoRenew()).isFalse();
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
    }

    @Test
    void uncancellationTurnsAutoRenewBackOn() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        postSigned(revenueCatBody("CANCELLATION", user.getId(), Map.of()));

        postSigned(revenueCatBody("UNCANCELLATION", user.getId(), Map.of()));

        assertThat(subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().isAutoRenew()).isTrue();
    }

    @Test
    void expirationDowngradesDirectlyToFreeMirroringHandleHalted() {
        User user = createActiveRevenueCatUser("PREMIUM", "MONTHLY");

        postSigned(revenueCatBody("EXPIRATION", user.getId(), Map.of()));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(free.getId());
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(subscription.getPaymentProvider()).isNull();
        assertThat(subscription.getStorePlatform()).isNull();
        assertThat(subscription.isAutoRenew()).isTrue();
    }

    @Test
    void billingIssueSetsPastDueNotPaymentFailed() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");

        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of()));

        // findActiveOrTrial() deliberately excludes PAST_DUE (see SubscriptionRepository) --
        // findByUserIdOrderByCreatedAtDesc mirrors how RazorpayWebhookDispatcherIT's own
        // pendingSetsStatusToPastDueButDoesNotRevokeAccess test verifies the same status.
        Subscription reloaded = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).get(0);
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_PAST_DUE);
    }

    /** Design spec §2.1 invariants 1/2 -- at most one active paid subscription per user, owned by
     *  exactly one provider. A RevenueCat INITIAL_PURCHASE arriving for a user who already has a
     *  live RAZORPAY mandate (e.g. a client-side gate bypass, or the mobile app's cached
     *  hasBillingSubscription read being stale) must not silently clobber the existing Razorpay
     *  row -- that would orphan a real, still-charging Razorpay subscription that nothing in
     *  Fynora would ever reference again the moment payment_provider flips to REVENUECAT. Symmetric
     *  to §6.4's already-implemented web-side guard (BillingCheckoutService.checkout()), which
     *  blocks the opposite direction.
     */
    @Test
    void initialPurchaseDoesNotOverwriteAnExistingRazorpayOwnedSubscription() {
        User user = new User();
        user.setEmail("revenuecat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Subscription razorpaySubscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        razorpaySubscription.setPlanId(premium.getId());
        razorpaySubscription.setBillingCycle("MONTHLY");
        razorpaySubscription.setPaymentProvider("RAZORPAY");
        razorpaySubscription.setRazorpaySubscriptionId("sub_existing_razorpay_it");
        razorpaySubscription.setStatus(Subscription.STATUS_ACTIVE);
        razorpaySubscription.setAutoRenew(true);
        subscriptionRepository.save(razorpaySubscription);

        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        IapProduct product = new IapProduct();
        product.setProviderProductId("plus_monthly_it_" + UUID.randomUUID());
        product.setPlanId(plus.getId());
        product.setBillingCycle("MONTHLY");
        product.setPlatform("IOS");
        product = iapProductRepository.save(product);

        postSigned(revenueCatBody("INITIAL_PURCHASE", user.getId(),
                Map.of("product_id", product.getProviderProductId(), "store", "APP_STORE",
                        "original_transaction_id", "txn_conflict_it")));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPaymentProvider()).isEqualTo("RAZORPAY");
        assertThat(subscription.getRazorpaySubscriptionId()).isEqualTo("sub_existing_razorpay_it");
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
        assertThat(subscription.getRevenuecatOriginalTransactionId()).isNull();
    }

    /** Mandatory per design spec §11 -- the one event type with no Razorpay precedent to lean on. */
    @Test
    void productChangeReconcilesBothPlanAndBillingCycle() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        IapProduct yearlyPremium = new IapProduct();
        yearlyPremium.setProviderProductId("premium_yearly_it_" + UUID.randomUUID());
        yearlyPremium.setPlanId(premium.getId());
        yearlyPremium.setBillingCycle("YEARLY");
        yearlyPremium.setPlatform("IOS");
        yearlyPremium = iapProductRepository.save(yearlyPremium);

        postSigned(revenueCatBody("PRODUCT_CHANGE", user.getId(),
                Map.of("product_id", yearlyPremium.getProviderProductId(), "store", "APP_STORE")));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
        assertThat(subscription.getBillingCycle()).isEqualTo("YEARLY");
    }

    // --- shared fixtures for this class ---

    private User createActiveRevenueCatUser(String planCode, String billingCycle) {
        User user = new User();
        user.setEmail("revenuecat-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("RevenueCat IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan plan = planRepository.findByCode(planCode).orElseThrow();
        IapProduct product = new IapProduct();
        product.setProviderProductId(planCode.toLowerCase() + "_" + billingCycle.toLowerCase() + "_it_" + UUID.randomUUID());
        product.setPlanId(plan.getId());
        product.setBillingCycle(billingCycle);
        product.setPlatform("IOS");
        product = iapProductRepository.save(product);

        postSigned(revenueCatBody("INITIAL_PURCHASE", user.getId(),
                Map.of("product_id", product.getProviderProductId(), "store", "APP_STORE",
                        "original_transaction_id", "txn_" + UUID.randomUUID())));
        return user;
    }

    private String revenueCatBody(String type, UUID appUserId, Map<String, Object> extra) {
        long expirationEpochMs = Instant.now().plusSeconds(2_592_000).toEpochMilli();
        StringBuilder extraJson = new StringBuilder();
        extra.forEach((k, v) -> extraJson.append(",\"").append(k).append("\":\"").append(v).append("\""));
        return """
                {"event":{"type":"%s","app_user_id":"%s","expiration_at_ms":%d%s}}
                """.formatted(type, appUserId, expirationEpochMs, extraJson);
    }
}
