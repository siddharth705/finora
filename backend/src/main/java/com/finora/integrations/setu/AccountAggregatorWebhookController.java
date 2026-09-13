package com.finora.integrations.setu;

import com.finora.service.WebhookEventService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Unauthenticated by necessity -- Setu calls this directly, carrying no Finora session -- same
 * posture as RazorpayWebhookController. What replaces authentication is the signature header,
 * verified before anything else runs.
 *
 * <p>Signature scheme (HMAC-SHA256 over the raw body, hex-encoded, header name
 * X-Setu-Signature) is the most common webhook-signing convention and matches what this codebase
 * already does for Razorpay -- but is UNVERIFIED against real Setu documentation. Revisit the
 * header name and algorithm once real Setu sandbox webhook deliveries are available; this is
 * flagged, not guessed silently.
 */
@RestController
@RequestMapping("/api/v1/webhooks/setu")
public class AccountAggregatorWebhookController {

    private static final Logger log = LoggerFactory.getLogger(AccountAggregatorWebhookController.class);

    private final SetuProperties properties;
    private final WebhookEventService webhookEventService;
    private final AccountAggregatorWebhookDispatcher dispatcher;

    public AccountAggregatorWebhookController(SetuProperties properties, WebhookEventService webhookEventService,
                                               AccountAggregatorWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Setu-Signature") String signature,
                                         @RequestHeader(value = "X-Setu-Event-Id", required = false) String eventId,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!verifySignature(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        String eventType = json.optString("event", "unknown");
        String consentHandleId = json.optString("consentHandleId", null);
        Map<String, Object> fullBody = json.toMap();

        if (eventId == null) {
            dispatcher.dispatch(eventType, consentHandleId);
            return ResponseEntity.ok().build();
        }

        if (!webhookEventService.claim(eventId, "SETU", eventType, fullBody)) {
            log.info("Duplicate Setu webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, consentHandleId);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process Setu webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }

    private boolean verifySignature(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            String expectedHex = HexFormat.of().formatHex(expected);
            return expectedHex.equalsIgnoreCase(signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Unable to verify Setu webhook signature.", e);
            return false;
        }
    }
}
