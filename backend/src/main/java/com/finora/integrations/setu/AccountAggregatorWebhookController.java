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

    /**
     * Bug fix (found during post-implementation review): this used to compare hex strings with
     * plain {@code String.equalsIgnoreCase}, which short-circuits on the first differing
     * character -- a textbook timing side channel on the one thing standing between this
     * unauthenticated endpoint and an attacker who can dispatch arbitrary consent.approved/revoked
     * events. {@link java.security.MessageDigest#isEqual} is specified to take the same time
     * regardless of where (or whether) the arrays differ, which is why the JDK itself recommends
     * it for exactly this comparison. Malformed hex in the header (wrong length, non-hex
     * characters) is treated as "does not match" rather than allowed to throw a 500 that would
     * otherwise leak information about why verification failed.
     */
    private boolean verifySignature(String rawBody, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.getWebhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] provided = HexFormat.of().parseHex(signature);
            return java.security.MessageDigest.isEqual(expected, provided);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Unable to verify Setu webhook signature.", e);
            return false;
        } catch (IllegalArgumentException e) {
            // signature header was not valid hex (wrong length, non-hex characters, null handled
            // by Spring's @RequestHeader already requiring it) -- not a match, not a server error.
            return false;
        }
    }
}
