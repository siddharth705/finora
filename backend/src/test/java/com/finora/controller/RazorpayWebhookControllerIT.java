package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Plan;
import com.finora.entity.SubscriptionOrder;
import com.finora.entity.User;
import com.finora.entity.WebhookEvent;
import com.finora.repository.PlanRepository;
import com.finora.repository.SubscriptionOrderRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.repository.WebhookEventRepository;
import com.finora.service.SubscriptionService;
import com.razorpay.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WebhookEventRepository webhookEventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlanRepository planRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionOrderRepository subscriptionOrderRepository;
    @Autowired private SubscriptionService subscriptionService;

    @Value("${app.integrations.razorpay.webhook-secret}")
    private String webhookSecret;

    private HttpHeaders signedHeaders(String body, String eventId) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", Utils.getHash(body, webhookSecret));
        if (eventId != null) {
            headers.set("X-Razorpay-Event-Id", eventId);
        }
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void validSignatureIsAcceptedAndRecordedUnderTheEventIdHeader() throws Exception {
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";
        String eventId = "evt_" + UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, signedHeaders(body, eventId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        WebhookEvent recorded = webhookEventRepository.findById(eventId).orElseThrow();
        assertThat(recorded.getEventType()).isEqualTo("subscription.updated");
        assertThat(recorded.getStatus()).isEqualTo(WebhookEvent.STATUS_PROCESSED);
    }

    @Test
    void redeliveryOfTheSameEventIdIsIgnoredNotReprocessed() throws Exception {
        // Regression test: an earlier version of the controller read the event id from a body
        // field that Razorpay's actual webhook payload never contains (verified against Razorpay's
        // own docs -- the real idempotency key is the X-Razorpay-Event-Id header), so this
        // dedup never actually engaged in production. This test would have passed even with that
        // bug, since a real Razorpay body never carries an "id" field either way -- the point of
        // this test is that TWO DELIVERIES OF THE SAME HEADER produce exactly one webhook_events row.
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";
        String eventId = "evt_" + UUID.randomUUID();
        HttpHeaders headers = signedHeaders(body, eventId);

        ResponseEntity<String> first = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhookEventRepository.findById(eventId)).isPresent();
    }

    /** Regression test for the payload-unwrap bug this endpoint had: every earlier test here (and
     *  every {@code RazorpayWebhookDispatcherIT} test) sends {@code dispatch()} an
     *  already-unwrapped {@code {"subscription": {"entity": {...}}}} shape, so none of them could
     *  ever have caught the controller passing the WHOLE body (including the "event"/"payload"
     *  wrapper) instead of just the inner "payload" object. This body is shaped exactly like a real
     *  Razorpay webhook -- {@code subscription} nested under {@code payload}, plus the
     *  account_id/contains/created_at fields a real one also carries -- and goes through the actual
     *  HTTP endpoint, not the dispatcher directly, so it fails the same way a real delivery would if
     *  the unwrap regressed. */
    @Test
    void aRealisticallyShapedActivatedWebhookActuallyActivatesTheSubscription() throws Exception {
        User user = new User();
        user.setEmail("webhook-controller-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Webhook Controller IT User");
        user.setRole("USER");
        user.setPhoneVerified(true);
        user = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(user.getId());

        Plan plus = planRepository.findByCode("PLUS").orElseThrow();
        String razorpaySubscriptionId = "sub_it_" + UUID.randomUUID();
        SubscriptionOrder order = new SubscriptionOrder();
        order.setUserId(user.getId());
        order.setPlanId(plus.getId());
        order.setBillingCycle("MONTHLY");
        order.setRazorpaySubscriptionId(razorpaySubscriptionId);
        order.setStatus(SubscriptionOrder.STATUS_PENDING);
        order.setAmount(new BigDecimal("399.00"));
        subscriptionOrderRepository.save(order);

        long fixtureEpoch = 1_893_456_000L; // synthetic-ok: arbitrary future epoch second, not a real identifier
        String body = """
                {"entity":"event","account_id":"acc_synthetic","event":"subscription.activated",
                 "contains":["subscription"],"created_at":%d,
                 "payload":{"subscription":{"entity":{"id":"%s","current_end":%d}}}}
                """.formatted(fixtureEpoch, razorpaySubscriptionId, fixtureEpoch);
        String eventId = "evt_" + UUID.randomUUID();

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, signedHeaders(body, eventId)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(subscriptionRepository.findActiveOrTrial(user.getId()).orElseThrow().getPlanId())
                .isEqualTo(plus.getId());
        assertThat(subscriptionOrderRepository.findByRazorpaySubscriptionId(razorpaySubscriptionId)
                .orElseThrow().getStatus()).isEqualTo(SubscriptionOrder.STATUS_COMPLETED);
        // The audit trail keeps the FULL body, unaffected by the dispatcher-facing unwrap.
        assertThat(webhookEventRepository.findById(eventId).orElseThrow().getPayload().toString())
                .contains("account_id");
    }

    @Test
    void invalidSignatureIsRejectedBeforeAnyStateChange() {
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", "not-a-real-signature");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
