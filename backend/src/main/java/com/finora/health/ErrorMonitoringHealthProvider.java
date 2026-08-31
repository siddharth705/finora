package com.finora.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mirrors MonitoringConfig's own no-op-degrade posture for a missing SENTRY_DSN (see that class's
 * doc comment): the app runs identically either way, but a worker dying mid-batch is visible only
 * in application logs rather than an alert. DEGRADED, not DOWN -- reduced operational visibility,
 * not a broken feature.
 */
@Component
public class ErrorMonitoringHealthProvider implements HealthProvider {

    private final String dsn;

    public ErrorMonitoringHealthProvider(@Value("${sentry.dsn:}") String dsn) {
        this.dsn = dsn;
    }

    @Override
    public String name() {
        return "Error Monitoring";
    }

    @Override
    public String category() {
        return "Platform";
    }

    @Override
    public HealthCheckResult check() {
        if (dsn != null && !dsn.isBlank()) {
            return HealthCheckResult.up("SENTRY_DSN configured -- backend errors are reported");
        }
        return HealthCheckResult.degraded("SENTRY_DSN unset -- backend error monitoring is off, "
                + "failures are visible only in application logs");
    }
}
