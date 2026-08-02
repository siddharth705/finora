package com.finora.health;

import com.finora.service.EmailProvider;
import com.finora.service.NoOpEmailProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailProviderHealthProviderTest {

    @Test
    void check_reportsUp_whenTheProviderIsConfigured() {
        EmailProvider emailProvider = mock(EmailProvider.class);
        when(emailProvider.isConfigured()).thenReturn(true);
        EmailProviderHealthProvider provider = new EmailProviderHealthProvider(emailProvider);

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }

    /** Mirrors ProductionConfigValidator's hard-fail severity for a missing RESEND_API_KEY --
     *  DOWN, not DEGRADED, since an unconfigured email provider means password-reset links leak
     *  into API responses instead of being emailed. */
    @Test
    void check_reportsDown_whenTheProviderIsNotConfigured() {
        EmailProviderHealthProvider provider = new EmailProviderHealthProvider(new NoOpEmailProvider());

        HealthCheckResult result = provider.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.detail()).contains("RESEND_API_KEY");
    }
}
