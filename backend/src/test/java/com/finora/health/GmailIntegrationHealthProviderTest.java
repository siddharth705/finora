package com.finora.health;

import com.finora.integrations.google.GoogleOAuthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GmailIntegrationHealthProviderTest {

    @Test
    void check_reportsUp_whenConfigured() {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId("client");
        properties.setClientSecret("secret");
        properties.setRedirectUri("https://app.example.com/callback");
        GmailIntegrationHealthProvider provider = new GmailIntegrationHealthProvider(properties);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    /** Unconfigured is a supported state per GoogleOAuthProperties's own doc comment -- DEGRADED,
     *  not DOWN: the feature is off, not broken. */
    @Test
    void check_reportsDegraded_whenNotConfigured() {
        GmailIntegrationHealthProvider provider = new GmailIntegrationHealthProvider(new GoogleOAuthProperties());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
    }
}
