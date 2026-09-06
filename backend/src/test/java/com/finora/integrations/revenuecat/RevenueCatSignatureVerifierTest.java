package com.finora.integrations.revenuecat;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RevenueCatSignatureVerifierTest {

    private static final String SECRET = "test-signing-secret";

    private String header(String body, long unixTimestamp, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal((unixTimestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return "t=" + unixTimestamp + ",v1=" + hex;
    }

    @Test
    void aCorrectlySignedRecentBodyIsAccepted() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, now, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isTrue();
    }

    @Test
    void aTamperedBodyIsRejected() throws Exception {
        String signedBody = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        String tamperedBody = "{\"event\":{\"type\":\"EXPIRATION\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(tamperedBody, header(signedBody, now, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }

    @Test
    void aSignatureFromTheWrongSecretIsRejected() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long now = Instant.now().getEpochSecond();

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, now, "wrong-secret"), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }

    @Test
    void aTimestampOutsideToleranceIsRejectedEvenWithACorrectSignature() throws Exception {
        String body = "{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}";
        long tenMinutesAgo = Instant.now().getEpochSecond() - 600;

        boolean valid = RevenueCatSignatureVerifier.verify(body, header(body, tenMinutesAgo, SECRET), SECRET, Duration.ofMinutes(5));

        assertThat(valid).isFalse();
    }
}
