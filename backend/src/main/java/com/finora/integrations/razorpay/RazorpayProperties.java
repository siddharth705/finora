package com.finora.integrations.razorpay;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Subscription billing V1. Same "unconfigured is a supported state" posture as
 * {@code GoogleOAuthProperties} — no Razorpay account exists yet (design spec §10), so every
 * consumer of this class must degrade cleanly (a 503, not a NullPointerException) rather than
 * assume these are always present. Not a boot-time requirement the way the JWT signing key is: a
 * missing Razorpay credential disables one payment integration, it does not risk a security
 * control.
 */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.razorpay")
public class RazorpayProperties {

    private String keyId;
    private String keySecret;
    private String webhookSecret;

    public boolean isConfigured() {
        return notBlank(keyId) && notBlank(keySecret) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getKeySecret() { return keySecret; }
    public void setKeySecret(String keySecret) { this.keySecret = keySecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
