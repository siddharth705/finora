package com.finora.health;

import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

/**
 * Wraps the same Actuator HealthEndpoint bean AdminSystemService already uses -- one real
 * indicator (db + diskSpace + ping, Actuator's own defaults), not a reimplementation. Reports
 * DEGRADED rather than DOWN for Actuator's OUT_OF_SERVICE/UNKNOWN statuses -- those mean "not
 * confidently UP," not necessarily "the database is unreachable," so collapsing them straight to
 * DOWN would overstate the alert.
 */
@Component
public class DatabaseHealthProvider implements HealthProvider {

    private final HealthEndpoint healthEndpoint;

    public DatabaseHealthProvider(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Override
    public String name() {
        return "Database";
    }

    @Override
    public String category() {
        return "Platform";
    }

    @Override
    public HealthCheckResult check() {
        HealthComponent root = healthEndpoint.health();
        Status status = root.getStatus();

        int total = 1;
        int up = status == Status.UP ? 1 : 0;
        if (root instanceof CompositeHealth composite) {
            total = composite.getComponents().size();
            up = (int) composite.getComponents().values().stream()
                    .filter(c -> c.getStatus() == Status.UP)
                    .count();
        }
        String detail = up + " of " + total + " Actuator indicators UP";

        if (status == Status.UP) return HealthCheckResult.up(detail);
        if (status == Status.DOWN) return HealthCheckResult.down(detail);
        return HealthCheckResult.degraded(detail);
    }
}
