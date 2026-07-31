package com.finora.service;

import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.health.HealthCheckResult;
import com.finora.health.HealthProvider;
import com.finora.health.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the "worst status wins" rollup and that providers are picked up purely by being in the
 *  injected list -- no registration step, matching HealthProvider's own extensibility claim. */
class AdminHealthRegistryServiceTest {

    private HealthProvider providerReturning(String name, String category, HealthStatus status) {
        return new HealthProvider() {
            public String name() { return name; }
            public String category() { return category; }
            public HealthCheckResult check() { return new HealthCheckResult(status, "detail for " + name); }
        };
    }

    @Test
    void platformHealth_isUp_whenEveryProviderIsUp() {
        AdminHealthRegistryService service = new AdminHealthRegistryService(List.of(
                providerReturning("Database", "Platform", HealthStatus.UP),
                providerReturning("Statement Import Pipeline", "Financial Intelligence", HealthStatus.UP)
        ));

        PlatformHealthDto health = service.platformHealth();

        assertThat(health.overallStatus()).isEqualTo("UP");
        assertThat(health.providers()).hasSize(2);
    }

    @Test
    void platformHealth_isDegraded_whenAnyProviderIsDegradedButNoneAreDown() {
        AdminHealthRegistryService service = new AdminHealthRegistryService(List.of(
                providerReturning("Database", "Platform", HealthStatus.UP),
                providerReturning("Statement Import Pipeline", "Financial Intelligence", HealthStatus.DEGRADED)
        ));

        assertThat(service.platformHealth().overallStatus()).isEqualTo("DEGRADED");
    }

    @Test
    void platformHealth_isDown_wheneverAnyProviderIsDown_evenIfOthersAreDegraded() {
        AdminHealthRegistryService service = new AdminHealthRegistryService(List.of(
                providerReturning("Database", "Platform", HealthStatus.DOWN),
                providerReturning("Statement Import Pipeline", "Financial Intelligence", HealthStatus.DEGRADED)
        ));

        assertThat(service.platformHealth().overallStatus()).isEqualTo("DOWN");
    }

    @Test
    void platformHealth_isUp_whenThereAreNoProvidersRegisteredAtAll() {
        AdminHealthRegistryService service = new AdminHealthRegistryService(List.of());

        PlatformHealthDto health = service.platformHealth();

        assertThat(health.overallStatus()).isEqualTo("UP");
        assertThat(health.providers()).isEmpty();
    }
}
