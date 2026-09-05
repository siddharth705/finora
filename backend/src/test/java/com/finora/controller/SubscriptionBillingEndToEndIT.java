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
import static org.mockito.Mockito.when;

/**
 * The full checkout -> webhook -> entitlements -> renewal -> billing-history path, end to end.
 * Design spec §11's "every state transition has an explicit test, not just the happy path" is
 * satisfied by RazorpayWebhookDispatcherIT's per-event coverage; this test's job is different --
 * proving the pieces actually compose through real HTTP and a real Spring context, which no
 * single-service test can show.
 */
class SubscriptionBillingEndToEndIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private RazorpayWebhookDispatcher dispatcher; // webhook signature verification is
                                                              // covered by RazorpayWebhookControllerIT;
                                                              // this test drives the dispatcher
                                                              // directly to keep focus on state, not
                                                              // signature plumbing.

    @MockitoBean private RazorpaySubscriptionGateway gateway;

    private User createUser() {
        User user = new User();
        user.setEmail("e2e-billing-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("End To End Billing IT User");
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
    void checkoutActivationRenewalAndBillingHistoryAllComposeCorrectly() {
        User user = createUser();
        Plan premium = planRepository.findByCode("PREMIUM").orElseThrow();
        BillingPrice price = billingPriceRepository
                .findByPlanIdAndBillingCycleAndActiveTrue(premium.getId(), BillingPrice.CYCLE_MONTHLY)
                .orElseThrow();
        String razorpayPlanId = "plan_e2e_" + UUID.randomUUID();
        price.setRazorpayPlanId(razorpayPlanId);
        billingPriceRepository.save(price);
        String razorpaySubscriptionId = "sub_e2e_" + UUID.randomUUID();

        when(gateway.isConfigured()).thenReturn(true);
        when(gateway.createSubscription(eq(razorpayPlanId), eq("MONTHLY"), anyMap()))
                .thenReturn(new RazorpaySubscriptionDto(razorpaySubscriptionId, "created"));

        // 1. Checkout.
        ResponseEntity<String> checkoutResponse = restTemplate.postForEntity("/api/v1/billing/checkout",
                new HttpEntity<>("{\"planCode\":\"PREMIUM\",\"billingCycle\":\"MONTHLY\"}", bearerFor(user)),
                String.class);
        assertThat(checkoutResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2. Entitlements still reflect Free -- the frontend success page never activates anything.
        ResponseEntity<String> entitlementsBeforeActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsBeforeActivation.getBody()).contains("\"planCode\":\"FREE\"");

        // 3. Activation webhook arrives.
        dispatcher.dispatch("subscription.activated", Map.of(
                "subscription", Map.of("entity", Map.of("id", razorpaySubscriptionId, "current_end", 1893456000L)))); // synthetic-ok: fixture epoch second

        // 4. Entitlements now reflect Premium.
        ResponseEntity<String> entitlementsAfterActivation = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(entitlementsAfterActivation.getBody()).contains("\"planCode\":\"PREMIUM\"");
        assertThat(entitlementsAfterActivation.getBody()).contains("\"FINO_AI\":true");

        // 5. A renewal webhook arrives a cycle later.
        dispatcher.dispatch("subscription.charged", Map.of(
                "payment", Map.of("entity", Map.of("id", "pay_e2e_1", "amount", 79900)),
                "subscription", Map.of("entity", Map.of(
                        "id", razorpaySubscriptionId, "plan_id", razorpayPlanId, "current_end", 1896134400L)))); // synthetic-ok: fixture epoch second

        // 6. Billing history now shows the payment -- BillingHistoryService/Controller needed no
        // changes of their own for this; they were always correct, just fed by nothing until now.
        ResponseEntity<String> history = restTemplate.exchange(
                "/api/v1/billing/history", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(history.getBody()).contains("799.0");
        assertThat(history.getBody()).contains("SUCCESS");
    }
}
