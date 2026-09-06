package com.finora.controller;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** Shared by RevenueCatWebhookControllerIT's tests -- computes a real signature the same way
 *  RevenueCatSignatureVerifier checks one, mirroring RazorpayWebhookControllerIT's own
 *  signedHeaders() helper (that one delegates to the Razorpay SDK's Utils.getHash instead, since
 *  Razorpay ships one; RevenueCat's SDK does not expose an equivalent, so this is hand-rolled). */
final class TestHmac {
    private TestHmac() {}

    static String header(String body, long unixTimestamp, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal((unixTimestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return "t=" + unixTimestamp + ",v1=" + hex;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
