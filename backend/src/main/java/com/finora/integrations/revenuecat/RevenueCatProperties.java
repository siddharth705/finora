package com.finora.integrations.revenuecat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Subscription billing V4. Same "unconfigured is a supported state" posture as RazorpayProperties
 *  -- a missing RevenueCat credential disables mobile IAP, nothing else. */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.revenuecat")
public class RevenueCatProperties {

    private String webhookSigningSecret;

    public boolean isConfigured() {
        return webhookSigningSecret != null && !webhookSigningSecret.isBlank();
    }

    public String getWebhookSigningSecret() { return webhookSigningSecret; }
    public void setWebhookSigningSecret(String webhookSigningSecret) { this.webhookSigningSecret = webhookSigningSecret; }
}
