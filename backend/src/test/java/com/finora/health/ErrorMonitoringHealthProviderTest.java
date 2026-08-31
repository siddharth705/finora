package com.finora.health;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMonitoringHealthProviderTest {

    @Test
    void check_reportsUp_whenDsnConfigured() {
        ErrorMonitoringHealthProvider provider = new ErrorMonitoringHealthProvider("https://key@sentry.io/1"); // synthetic-ok: placeholder Sentry DSN format, not a real value

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void check_reportsDegraded_whenDsnUnset() {
        ErrorMonitoringHealthProvider provider = new ErrorMonitoringHealthProvider("");

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
    }
}
