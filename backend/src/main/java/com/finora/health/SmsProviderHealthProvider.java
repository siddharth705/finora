package com.finora.health;

import com.finora.service.SilentProductionFallback;
import com.finora.service.SmsProvider;
import org.springframework.stereotype.Component;

/**
 * Mirrors ProductionConfigValidator's soft-warning severity for a missing TWO_FACTOR_API_KEY (see
 * that class's own doc comment) -- DEGRADED, not DOWN, since transaction-alert SMS is a
 * best-effort notification, not a security control; NoOpSmsProvider's fallback just logs instead
 * of failing anything a user depends on.
 */
@Component
public class SmsProviderHealthProvider implements HealthProvider {

    private final SmsProvider smsProvider;

    public SmsProviderHealthProvider(SmsProvider smsProvider) {
        this.smsProvider = smsProvider;
    }

    @Override
    public String name() {
        return "SMS Provider";
    }

    @Override
    public String category() {
        return "Notifications";
    }

    @Override
    public HealthCheckResult check() {
        if (smsProvider.isConfigured()) {
            return HealthCheckResult.up("Configured -- transaction alert SMS is sent for real");
        }
        String hint = smsProvider instanceof SilentProductionFallback fallback
                ? fallback.requiredConfigHint() : "its API key";
        return HealthCheckResult.degraded("Not configured (" + hint + " unset) -- transaction alert SMS is logged only, never actually sent");
    }
}
