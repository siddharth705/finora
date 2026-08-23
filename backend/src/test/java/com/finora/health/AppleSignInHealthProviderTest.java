package com.finora.health;

import com.finora.integrations.apple.login.AppleLoginProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AppleSignInHealthProviderTest {

    @Test
    void check_reportsUp_whenClientIdsConfigured() {
        AppleLoginProperties properties = new AppleLoginProperties();
        properties.setClientIds(List.of("com.finoratech.app"));
        AppleSignInHealthProvider provider = new AppleSignInHealthProvider(properties);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDegraded_whenNoClientIdsConfigured() {
        AppleSignInHealthProvider provider = new AppleSignInHealthProvider(new AppleLoginProperties());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
    }
}
