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
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BillingControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("billing-checkout-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Checkout IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void checkoutCreatesARazorpaySubscriptionAndReturnsItsId() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        price.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(price);

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(price.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_test_123", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/checkout", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("sub_test_123");
    }

    @Test
    void cancelCallsRazorpayAndSetsAutoRenewFalse() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        String razorpaySubscriptionId = "sub_test_" + UUID.randomUUID();
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        subscription.setRazorpaySubscriptionId(razorpaySubscriptionId);
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/cancel", new HttpEntity<>(null, bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).cancelSubscription(eq(razorpaySubscriptionId), eq(true));

        var reloaded = subscriptionRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId).orElseThrow();
        assertThat(reloaded.isAutoRenew()).isFalse();
    }

    @Test
    void changePlanSchedulesADowngradeForAnExistingPaidSubscriber() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        subscription.setPlanId(premium.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PLUS").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        plusMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(plusMonthly);

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(gateway).updateSubscription(eq(subscription.getRazorpaySubscriptionId()),
                eq(plusMonthly.getRazorpayPlanId()), eq(true));
    }

    @Test
    void changePlanReturnsCheckoutDetailsForAnUpgrade() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscriptionRepository.save(subscription);

        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(planRepository.findByCode("PREMIUM").orElseThrow().getId(), "MONTHLY")
                .orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        when(gateway.createSubscription(eq(premiumMonthly.getRazorpayPlanId()), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_new_" + UUID.randomUUID(), "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/billing/change-plan", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("razorpaySubscriptionId").contains("keyId");
    }

    @Test
    void mySubscriptionReportsThePlanAndRenewalDate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow();
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        subscription.setPlanId(plus.getId());
        subscription.setBillingCycle("MONTHLY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRenewalDate(java.time.LocalDate.of(2026, 11, 1));
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/subscription", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"planCode\":\"PLUS\"").contains("2026-11-01").contains("\"hasBillingSubscription\":true");
    }

    @Test
    void aSecondCheckoutForTheSamePlanResumesTheFirstInsteadOfCreatingAnother() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.isConfigured()).thenReturn(true);
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_test_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        when(gateway.createSubscription(any(), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_first_attempt", "created"));

        HttpEntity<String> request = new HttpEntity<>(
                "{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user));
        ResponseEntity<String> first = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);
        assertThat(first.getBody()).contains("sub_first_attempt");

        ResponseEntity<String> second = restTemplate.postForEntity("/api/v1/billing/checkout", request, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).contains("sub_first_attempt");
        verify(gateway, times(1)).createSubscription(any(), any(), anyMap());
    }

    @Test
    void cancellingAPendingOrderAllowsCheckingOutADifferentPlanAfterwards() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        when(gateway.isConfigured()).thenReturn(true);
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice premiumMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), "MONTHLY").orElseThrow();
        premiumMonthly.setRazorpayPlanId("plan_prem_" + UUID.randomUUID());
        billingPriceRepository.save(premiumMonthly);
        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        BillingPrice plusMonthly = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(plus.getId(), "MONTHLY").orElseThrow();
        plusMonthly.setRazorpayPlanId("plan_plus_" + UUID.randomUUID());
        billingPriceRepository.save(plusMonthly);
        when(gateway.createSubscription(eq(premiumMonthly.getRazorpayPlanId()), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_premium_attempt", "created"));
        when(gateway.createSubscription(eq(plusMonthly.getRazorpayPlanId()), any(), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto("sub_plus_attempt", "created"));

        restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);

        ResponseEntity<String> blocked = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<String> cancel = restTemplate.postForEntity(
                "/api/v1/billing/pending-order/cancel", new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(cancel.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> retried = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PLUS\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)), String.class);
        assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retried.getBody()).contains("sub_plus_attempt");
    }
}
