package com.finora.health;

import com.finora.integrations.google.login.GoogleLoginProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleSignInHealthProviderTest {

    @Test
    void check_reportsUp_whenClientIdsConfigured() {
        GoogleLoginProperties properties = new GoogleLoginProperties();
        properties.setClientIds(List.of("web-client-id"));
        GoogleSignInHealthProvider provider = new GoogleSignInHealthProvider(properties);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDegraded_whenNoClientIdsConfigured() {
        GoogleSignInHealthProvider provider = new GoogleSignInHealthProvider(new GoogleLoginProperties());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
    }
}
