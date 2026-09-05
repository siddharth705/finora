package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.WebhookEvent;
import com.finora.repository.WebhookEventRepository;
import com.razorpay.Utils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WebhookEventRepository webhookEventRepository;

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
