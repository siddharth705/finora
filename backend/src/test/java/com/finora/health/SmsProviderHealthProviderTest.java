package com.finora.health;

import com.finora.service.NoOpSmsProvider;
import com.finora.service.SmsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmsProviderHealthProviderTest {

    @Test
    void check_reportsUp_whenTheProviderIsConfigured() {
        SmsProvider smsProvider = mock(SmsProvider.class);
        when(smsProvider.isConfigured()).thenReturn(true);
        SmsProviderHealthProvider provider = new SmsProviderHealthProvider(smsProvider);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    /** Mirrors ProductionConfigValidator's soft-warning severity for a missing TWO_FACTOR_API_KEY
     *  -- DEGRADED, not DOWN, since transaction-alert SMS is a best-effort notification. */
    @Test
    void check_reportsDegraded_whenTheProviderIsNotConfigured() {
        SmsProviderHealthProvider provider = new SmsProviderHealthProvider(new NoOpSmsProvider());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DEGRADED);
        assertThat(result.detail()).contains("TWO_FACTOR_API_KEY");
    }
}
