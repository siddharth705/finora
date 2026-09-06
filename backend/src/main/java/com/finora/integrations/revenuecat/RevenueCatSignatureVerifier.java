package com.finora.integrations.revenuecat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Subscription billing V4. Verifies RevenueCat's HMAC webhook signature
 *  ({@code X-RevenueCat-Webhook-Signature: t=<unix_ts>,v1=<hex>}, HMAC-SHA256 over
 *  "{timestamp}.{raw_body}") -- verified against RevenueCat's own current docs, not assumed (design
 *  spec §3). {@code rawBody} must be the exact bytes RevenueCat sent, never a re-serialized object,
 *  same requirement Razorpay's own verification has (Utils.verifyWebhookSignature). */
public final class RevenueCatSignatureVerifier {

    private RevenueCatSignatureVerifier() {}

    public static boolean verify(String rawBody, String signatureHeader, String secret, Duration tolerance) {
        if (signatureHeader == null) return false;
        Map<String, String> parts = new HashMap<>();
        for (String part : signatureHeader.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2) parts.put(kv[0], kv[1]);
        }
        String timestampPart = parts.get("t");
        String signaturePart = parts.get("v1");
        if (timestampPart == null || signaturePart == null) return false;

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampPart);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > tolerance.toSeconds()) return false;

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((timestampPart + "." + rawBody).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return MessageDigest.isEqual(hex.toString().getBytes(StandardCharsets.UTF_8),
                    signaturePart.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }
}
