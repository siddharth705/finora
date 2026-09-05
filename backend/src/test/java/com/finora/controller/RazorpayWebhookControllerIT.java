package com.finora.controller;

import com.finora.AbstractIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayWebhookControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private WebhookEventRepository webhookEventRepository;

    @Value("${app.integrations.razorpay.webhook-secret}")
    private String webhookSecret;

    @Test
    void validSignatureIsAcceptedAndRecorded() throws Exception {
        String body = "{\"event\":\"subscription.updated\",\"payload\":{}}";
        String signature = Utils.getHash(body, webhookSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Razorpay-Signature", signature);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/v1/webhooks/razorpay", new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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
