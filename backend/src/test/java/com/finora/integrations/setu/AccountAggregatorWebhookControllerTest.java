package com.finora.integrations.setu;

import com.finora.service.WebhookEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AccountAggregatorWebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";

    private SetuProperties properties;
    private WebhookEventService webhookEventService;
    private AccountAggregatorWebhookDispatcher dispatcher;
    private AccountAggregatorWebhookController controller;

    @BeforeEach
    void setUp() {
        properties = new SetuProperties();
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setWebhookSecret(SECRET);

        webhookEventService = mock(WebhookEventService.class);
        dispatcher = mock(AccountAggregatorWebhookDispatcher.class);
        controller = new AccountAggregatorWebhookController(properties, webhookEventService, dispatcher);

        when(webhookEventService.claim(any(), any(), any(), any())).thenReturn(true);
    }

    @Test
    void rejectsAnInvalidSignature() {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        ResponseEntity<Void> response = controller.receive("not-the-real-signature", "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void acceptsAValidSignatureAndDispatches() throws Exception {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        String signature = hmacSha256Hex(body, SECRET);

        ResponseEntity<Void> response = controller.receive(signature, "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(dispatcher).dispatch("consent.revoked", "consent-1");
        verify(webhookEventService).markProcessed("event-1");
    }

    @Test
    void duplicateEventIdIsNotDispatchedTwice() throws Exception {
        String body = "{\"event\":\"consent.revoked\",\"consentHandleId\":\"consent-1\"}";
        String signature = hmacSha256Hex(body, SECRET);
        when(webhookEventService.claim("event-1", "SETU", "consent.revoked", java.util.Map.of(
                "event", "consent.revoked", "consentHandleId", "consent-1"))).thenReturn(false);

        ResponseEntity<Void> response = controller.receive(signature, "event-1", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(dispatcher);
    }

    private static String hmacSha256Hex(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] out = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(out);
    }
}
