package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.BillingPrice;
import com.finora.entity.Plan;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionDto;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.BillingPriceRepository;
import com.finora.repository.PlanRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The upgrade and downgrade paths end to end, on top of Plan 1's already-merged infrastructure.
 * Plan 1's SubscriptionBillingEndToEndIT covers first-time checkout/activation/renewal/history;
 * this test's job is the two flows Plan 2 adds.
 */
class SubscriptionUpgradeDowngradeEndToEndIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private RazorpayWebhookDispatcher dispatcher;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("upgrade-downgrade-e2e-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Upgrade Downgrade E2E IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());
        return saved;
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void downgradeSchedulesNowAndReconcilesAtTheNextCharge() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        String downgradeRazorpayPlanId = "plan_e2e_" + UUID.randomUUID(); // stays under razorpay_plan_id's VARCHAR(50)
        plusMonthly.setRazorpayPlanId(downgradeRazorpayPlanId);
        billingPriceRepository.save(plusMonthly);
        String razorpaySubscriptionId = "sub_e2e_" + UUID.randomUUID();

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        // 1. Request the downgrade.
        ResponseEntity<String> changePlanResponse = restTemplate.postForEntity("/api/v1/billing/change-plan",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).updateSubscription(razorpaySubscriptionId, downgradeRazorpayPlanId, true);

        // 2. Entitlements still reflect Premium -- the downgrade hasn't taken effect yet.
        ResponseEntity<String> entitlementsBeforeCharge = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeCharge.getBody()).contains("\"planCode\":\"PREMIUM\"");

        // 3. At cycle end, Razorpay charges the NEW (lower) plan -- Plan 1's existing handleCharged
        // reconciliation is what actually applies the downgrade, unmodified by this plan.
        dispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_e2e_1", "amount", 39900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", downgradeRazorpayPlanId, "current_end", 1896134400L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Plus.
        ResponseEntity<String> entitlementsAfterCharge = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterCharge.getBody()).contains("\"planCode\":\"PLUS\"");
    }

    @Test
    void upgradeActivatesTheNewSubscriptionAndCancelsTheOldOne() {
        User user = createUser();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        String upgradeRazorpayPlanId = "plan_e2e_" + UUID.randomUUID(); // stays under razorpay_plan_id's VARCHAR(50)
        premiumMonthly.setRazorpayPlanId(upgradeRazorpayPlanId);
        billingPriceRepository.save(premiumMonthly);
        String oldRazorpaySubscriptionId = "sub_e2e_old_" + UUID.randomUUID();
        String newRazorpaySubscriptionId = "sub_e2e_new_" + UUID.randomUUID();

        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId(oldRazorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        when(gateway.createSubscription(eq(upgradeRazorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto(newRazorpaySubscriptionId, "created"));

        // 1. Request the upgrade -- a real, external, second Razorpay subscription is created.
        ResponseEntity<String> changePlanResponse = restTemplate.postForEntity("/api/v1/billing/change-plan",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Entitlements still reflect Plus -- never granted from this call's own return value.
        ResponseEntity<String> entitlementsBeforeActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeActivation.getBody()).contains("\"planCode\":\"PLUS\"");

        // 3. The new subscription's activation webhook arrives.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of("id", newRazorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Premium, and the old mandate was stopped.
        ResponseEntity<String> entitlementsAfterActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterActivation.getBody()).contains("\"planCode\":\"PREMIUM\"");
        verify(gateway).cancelSubscription(oldRazorpaySubscriptionId, false);

        var reloaded = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        assertThat(reloaded.getRazorpaySubscriptionId()).isEqualTo(newRazorpaySubscriptionId);
    }
}
