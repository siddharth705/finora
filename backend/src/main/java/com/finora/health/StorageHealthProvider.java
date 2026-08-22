package com.finora.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mirrors ProductionConfigValidator's fatal-boot severity for a missing
 * {@code app.statement-storage.provider} (see that class's own doc comment): without it, statement
 * bytes have nowhere durable to live. Either 'r2' or 'filesystem' (on a persistent volume) is a
 * fully valid configured state -- this reports which one is active rather than treating filesystem
 * as a lesser fallback, since the validator itself accepts both. DOWN here should only ever be
 * observable outside prod (dev/test can boot with storage unconfigured); in prod, unset means the
 * process never started at all.
 */
@Component
public class StorageHealthProvider implements HealthProvider {

    private final String provider;

    public StorageHealthProvider(@Value("${app.statement-storage.provider:}") String provider) {
        this.provider = provider;
    }

    @Override
    public String name() {
        return "Statement Storage";
    }

    @Override
    public String category() {
        return "Platform";
    }

    @Override
    public HealthCheckResult check() {
        if (provider == null || provider.isBlank()) {
            return HealthCheckResult.down("app.statement-storage.provider is unset -- uploaded statement "
                    + "files have no durable home");
        }
        return HealthCheckResult.up("Provider '" + provider + "' configured -- statement files are stored durably");
    }
}
