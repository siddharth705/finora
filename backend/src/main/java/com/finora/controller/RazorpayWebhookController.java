package com.finora.controller;

import com.finora.integrations.razorpay.RazorpayProperties;
import com.finora.service.RazorpayWebhookDispatcher;
import com.finora.service.WebhookEventService;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
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

import java.util.Map;

/**
 * Subscription billing V1 (design spec §4.7, §5). Unauthenticated by necessity -- Razorpay calls
 * this directly, carrying no Finora session -- same posture as
 * {@code GoogleOAuthController}'s callback endpoint. What replaces authentication is the signature
 * header, verified before anything else runs.
 *
 * <p>Takes the raw body as a {@code String}, not a typed DTO: signature verification is over the
 * exact bytes Razorpay sent, and re-serializing a deserialized object is not guaranteed to produce
 * byte-identical output.
 *
 * <p>The idempotency key is the {@code X-Razorpay-Event-Id} HEADER, not a body field -- verified
 * against Razorpay's own webhook docs ("You can identify the duplicate webhooks using the
 * x-razorpay-event-id header. The value for this header is unique per event.") and against a
 * sample payload, whose top level carries only {@code entity}/{@code account_id}/{@code event}/
 * {@code contains}/{@code payload}/{@code created_at} -- no event id at all. An earlier version of
 * this controller read {@code json.optString("id", null)} from the body, which is always absent, so
 * every webhook took the "unrecorded" fallback path and the {@code webhook_events} idempotency
 * ledger (design spec §4.7, described there as mandatory) never actually engaged in production.
 */
@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
public class RazorpayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(RazorpayWebhookController.class);

    private final RazorpayProperties properties;
    private final WebhookEventService webhookEventService;
    private final RazorpayWebhookDispatcher dispatcher;

    public RazorpayWebhookController(RazorpayProperties properties, WebhookEventService webhookEventService,
                                      RazorpayWebhookDispatcher dispatcher) {
        this.properties = properties;
        this.webhookEventService = webhookEventService;
        this.dispatcher = dispatcher;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestHeader("X-Razorpay-Signature") String signature,
                                         @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
                                         @RequestBody String rawBody) {
        if (!properties.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        boolean valid;
        try {
            valid = Utils.verifyWebhookSignature(rawBody, signature, properties.getWebhookSecret());
        } catch (RazorpayException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!valid) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        JSONObject json = new JSONObject(rawBody);
        String eventType = json.optString("event", "unknown");
        Map<String, Object> payload = json.toMap();

        // Razorpay's test-mode "send test webhook" tool does not always include this header -- fall
        // back to accepting (and not recording) rather than NPEing on a null primary key. A real
        // production webhook always carries one.
        if (eventId == null) {
            dispatcher.dispatch(eventType, payload);
            return ResponseEntity.ok().build();
        }

        if (!webhookEventService.claim(eventId, "RAZORPAY", eventType, payload)) {
            log.info("Duplicate Razorpay webhook event {} ({}), ignoring.", eventId, eventType);
            return ResponseEntity.ok().build();
        }

        try {
            dispatcher.dispatch(eventType, payload);
            webhookEventService.markProcessed(eventId);
        } catch (RuntimeException e) {
            webhookEventService.markFailed(eventId);
            log.error("Failed to process Razorpay webhook event {} ({}).", eventId, eventType, e);
            throw e;
        }
        return ResponseEntity.ok().build();
    }
}
