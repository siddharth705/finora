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

        postSigned(revenueCatBody("CANCELLATION", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(subscription.isAutoRenew()).isFalse();
        assertThat(subscription.getPaymentProvider()).isEqualTo("REVENUECAT");
    }

    @Test
    void uncancellationTurnsAutoRenewBackOn() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        postSigned(revenueCatBody("CANCELLATION", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        postSigned(revenueCatBody("UNCANCELLATION", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        assertThat(subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().isAutoRenew()).isTrue();
    }

    @Test
    void expirationDowngradesDirectlyToFreeMirroringHandleHalted() {
        User user = createActiveRevenueCatUser("PREMIUM", "MONTHLY");

        postSigned(revenueCatBody("EXPIRATION", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

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

        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        // findActiveOrTrial() deliberately excludes PAST_DUE (see SubscriptionRepository) --
        // findByUserIdOrderByCreatedAtDesc mirrors how RazorpayWebhookDispatcherIT's own
        // pendingSetsStatusToPastDueButDoesNotRevokeAccess test verifies the same status.
        Subscription reloaded = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).get(0);
        assertThat(reloaded.getStatus()).isEqualTo(Subscription.STATUS_PAST_DUE);
    }

    /** Real bug found in bug-hunt review, not in the original design doc: every handler except
     *  handleInitialPurchase originally looked the subscription up via subscriptionForAppUserId,
     *  which calls findActiveOrTrial(userId) -- filtered to ACTIVE/TRIAL only. Once BILLING_ISSUE
     *  sets status=PAST_DUE, that lookup returns empty for every subsequent event, so a successful
     *  retry's RENEWAL would silently no-op and the user would stay stuck in PAST_DUE forever, even
     *  though the store confirms payment succeeded. RazorpayWebhookDispatcher never has this
     *  problem: handleCharged/handlePending/handleHalted/handleCancelled all look up by the stable
     *  external id (findByRazorpaySubscriptionId), independent of the row's current status --
     *  RevenueCat's own analog of that stable id is original_transaction_id (design spec §4.1), so
     *  the fix mirrors Razorpay's own pattern exactly. */
    @Test
    void billingIssueThenRenewalReactivatesTheSubscriptionDespitePastDueStatus() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));
        assertThat(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).get(0).getStatus())
                .isEqualTo(Subscription.STATUS_PAST_DUE);

        postSigned(revenueCatBody("RENEWAL", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId())
                .orElseThrow(() -> new AssertionError("RENEWAL after BILLING_ISSUE did not reactivate the subscription"));
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
    }

    /** Same root cause as above, other direction: retries exhausted (EXPIRATION) after a
     *  BILLING_ISSUE must still downgrade to Free -- a subscription stuck in PAST_DUE forever,
     *  never reachable by the event that's supposed to end it, is the worse failure mode of the two
     *  (a user who should have lost paid access keeps it indefinitely). */
    @Test
    void billingIssueThenExpirationStillDowngradesToFreeDespitePastDueStatus() {
        User user = createActiveRevenueCatUser("PREMIUM", "MONTHLY");
        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        postSigned(revenueCatBody("EXPIRATION", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan free = planRepository.findByCode("FREE").orElseThrow();
        assertThat(subscription.getPlanId()).isEqualTo(free.getId());
        assertThat(subscription.getPaymentProvider()).isNull();
    }

    /** Real bug found in bug-hunt review, verified against RevenueCat's own docs (event-flows.md):
     *  a billing issue ALWAYS fires BILLING_ISSUE and a companion CANCELLATION event with
     *  cancel_reason=BILLING_ERROR together -- not a distinct user cancellation. Before this fix,
     *  handleCancellation flipped auto_renew=false unconditionally, so a healthy subscription that
     *  later recovered via a real RENEWAL (RevenueCat's own subscription-lifecycle docs confirm
     *  RENEWAL is the recovery signal) would stay permanently mislabeled "won't renew" -- handleRenewal
     *  never restores auto_renew. */
    @Test
    void billingErrorCancellationDoesNotFlipAutoRenewUnlikeARealUserCancellation() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");

        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));
        postSigned(revenueCatBody("CANCELLATION", user.getId(),
                Map.of("original_transaction_id", txnIdFor(user), "cancel_reason", "BILLING_ERROR")));

        Subscription pastDue = subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).get(0);
        assertThat(pastDue.isAutoRenew()).isTrue();

        postSigned(revenueCatBody("RENEWAL", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));

        var recovered = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(recovered.isAutoRenew()).isTrue();
    }

    /** Same root cause as the two tests above, third variant: a user stuck PAST_DUE cancels through
     *  the store's own UI and resubscribes fresh right there (bypassing Fynora's Paywall, which
     *  isn't reachable while a REVENUECAT-owned row exists per §6.3) -- RevenueCat sends a genuine
     *  new INITIAL_PURCHASE with a NEW original_transaction_id for the same app_user_id. Before this
     *  fix, handleInitialPurchase's own lookup (subscriptionForAppUserId -> findActiveOrTrial) would
     *  ALSO have missed the still-PAST_DUE row and silently dropped this real, paid purchase. */
    @Test
    void initialPurchaseActivatesEvenWhenTheExistingRowIsCurrentlyPastDue() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        postSigned(revenueCatBody("BILLING_ISSUE", user.getId(), Map.of("original_transaction_id", txnIdFor(user))));
        assertThat(subscriptionRepository.findByUserIdOrderByCreatedAtDesc(user.getId()).get(0).getStatus())
                .isEqualTo(Subscription.STATUS_PAST_DUE);

        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        IapProduct freshProduct = new IapProduct();
        freshProduct.setProviderProductId("premium_monthly_it_" + UUID.randomUUID());
        freshProduct.setPlanId(premium.getId());
        freshProduct.setBillingCycle("MONTHLY");
        freshProduct.setPlatform("IOS");
        freshProduct = iapProductRepository.save(freshProduct);

        postSigned(revenueCatBody("INITIAL_PURCHASE", user.getId(),
                Map.of("product_id", freshProduct.getProviderProductId(), "store", "APP_STORE",
                        "original_transaction_id", "txn_fresh_" + UUID.randomUUID())));

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId())
                .orElseThrow(() -> new AssertionError("Fresh INITIAL_PURCHASE after PAST_DUE was not activated"));
        assertThat(subscription.getStatus()).isEqualTo(Subscription.STATUS_ACTIVE);
        assertThat(subscription.getPlanId()).isEqualTo(premium.getId());
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

    /** Mandatory per design spec §11 -- the one event type with no Razorpay precedent to lean on.
     *  Real bug found in bug-hunt review: RevenueCat's own docs (event-types-and-fields.md,
     *  cross-checked against four independent sample payloads) are explicit that on PRODUCT_CHANGE,
     *  {@code product_id} is the OLD product the subscriber switched FROM -- the plan they're
     *  leaving -- and {@code new_product_id} is the one they switched TO. This test deliberately
     *  sets {@code product_id} to a mapping that does NOT exist in iap_products (the old plan
     *  wouldn't need a fresh lookup) to prove the handler reads new_product_id, not product_id. */
    @Test
    void productChangeReconcilesBothPlanAndBillingCycleUsingNewProductIdNotProductId() {
        User user = createActiveRevenueCatUser("PLUS", "MONTHLY");
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        IapProduct yearlyPremium = new IapProduct();
        yearlyPremium.setProviderProductId("premium_yearly_it_" + UUID.randomUUID());
        yearlyPremium.setPlanId(premium.getId());
        yearlyPremium.setBillingCycle("YEARLY");
        yearlyPremium.setPlatform("IOS");
        yearlyPremium = iapProductRepository.save(yearlyPremium);

        postSigned(revenueCatBody("PRODUCT_CHANGE", user.getId(),
                Map.of("product_id", "old_product_with_no_iap_products_row_" + UUID.randomUUID(),
                        "new_product_id", yearlyPremium.getProviderProductId(), "store", "APP_STORE",
                        "original_transaction_id", txnIdFor(user))));

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
                        "original_transaction_id", txnIdFor(user))));
        return user;
    }

    /** Deterministic from the user id rather than a field returned by createActiveRevenueCatUser --
     *  lets every follow-up event in a test reference the same original_transaction_id its
     *  INITIAL_PURCHASE used, without threading an extra return value through every call site. */
    private String txnIdFor(User user) {
        return "txn_" + user.getId();
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
