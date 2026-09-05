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
import static org.mockito.Mockito.when;

class BillingControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private BillingPriceRepository billingPriceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

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
}
