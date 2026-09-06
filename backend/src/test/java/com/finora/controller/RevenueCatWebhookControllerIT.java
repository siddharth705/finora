package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.IapProduct;
import com.finora.entity.Plan;
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
}
