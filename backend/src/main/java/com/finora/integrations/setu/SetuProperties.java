package com.finora.integrations.setu;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** app.integrations.setu.* -- same isConfigured() shape as RazorpayProperties/GoogleOAuthProperties:
 *  computed from whether real credentials are present, not a separate boolean flag someone could
 *  forget to flip alongside the credentials themselves. {@code @Configuration} (not a bare
 *  {@code @ConfigurationProperties}) mirrors GoogleOAuthProperties -- required for this to be
 *  registered as a bean at all under this codebase's Spring Boot version. */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.setu")
public class SetuProperties {

    private String baseUrl;
    private String clientId;
    private String clientSecret;
    private String webhookSecret;

    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(webhookSecret);
    }

    private static boolean notBlank(String value) { return value != null && !value.isBlank(); }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getWebhookSecret() { return webhookSecret; }
    public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
}
