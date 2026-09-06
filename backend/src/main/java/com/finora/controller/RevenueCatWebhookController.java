package com.finora.controller;

import com.finora.integrations.revenuecat.RevenueCatProperties;
import com.finora.integrations.revenuecat.RevenueCatSignatureVerifier;
import com.finora.service.RevenueCatWebhookDispatcher;
import com.finora.service.WebhookEventService;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription billing V4 (design spec §3/§4.3/§7). Unauthenticated by necessity, same posture as
 * RazorpayWebhookController -- what replaces authentication is the HMAC signature, verified before
 * anything else runs, over the RAW body (never a re-parsed object).
 *
 * <p>Event ids are prefixed ("revenuecat:...") before being handed to the shared webhook_events
 * ledger -- see this design's own §4.3: a composite (provider, event_id) primary key would need a
 * composite JPA key and new WebhookEventService signatures; a prefix gets the identical
 * collision-safety with neither. RevenueCat's own event carries no top-level "id" in the minimal
 * shape used in this design's own tests, so a random id is generated when absent, exactly matching
 * RazorpayWebhookController's own "no event id header -- accept, don't record" fallback.
 */
@RestController
@RequestMapping("/api/v1/webhooks/revenuecat")
public class RevenueCatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RevenueCatWebhookController.class);
    private static final Duration SIGNATURE_TOLERANCE = Duration.ofMinutes(5);

    private final RevenueCatProperties properties;
    private final WebhookEventService webhookEventService;
    private final RevenueCatWebhookDispatcher dispatcher;

    public RevenueCatWebhookController(RevenueCatProperties properties, WebhookEventService webhookEventService,
                                        RevenueCatWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-RevenueCat-Webhook-Signature") String signature,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!RevenueCatSignatureVerifier.verify(rawBody, signature, properties.getWebhookSigningSecret(), SIGNATURE_TOLERANCE)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        JSONObject event = json.optJSONObject("event");
        if (event == null) return ResponseEntity.ok().build();
        String eventType = event.optString("type", "unknown");
        Map<String, Object> eventPayload = event.toMap();

        String rawEventId = event.optString("id", null);
        String eventId = "revenuecat:" + (rawEventId != null ? rawEventId : UUID.randomUUID());

        if (!webhookEventService.claim(eventId, "REVENUECAT", eventType, eventPayload)) {
            log.info("Duplicate RevenueCat webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, eventPayload);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process RevenueCat webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }
}
