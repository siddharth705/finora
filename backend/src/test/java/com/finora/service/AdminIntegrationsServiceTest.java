package com.finora.service;

import com.finora.dto.IntegrationsDto.IntegrationsOverviewDto;
import com.finora.health.HealthCheckResult;
import com.finora.health.HealthProvider;
import com.finora.health.HealthStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the whitelist/description layer this service adds on top of AdminHealthRegistryService:
 *  only providers with a curated description appear, internal engine checks (Database, Financial
 *  Intelligence Engine, Statement Import) are excluded, and upcoming carries no live status at all. */
class AdminIntegrationsServiceTest {

    private HealthProvider providerReturning(String name, String category, HealthStatus status) {
        return new HealthProvider() {
            public String name() { return name; }
            public String category() { return category; }
            public HealthCheckResult check() { return new HealthCheckResult(status, "detail for " + name); }
        };
    }

    @Test
    void overview_includesOnlyProvidersWithACuratedDescription() {
        AdminHealthRegistryService registry = new AdminHealthRegistryService(List.of(
                providerReturning("Database", "Platform", HealthStatus.UP),
                providerReturning("Financial Intelligence Engine", "Financial Intelligence", HealthStatus.UP),
                providerReturning("Gmail Sync", "Integrations", HealthStatus.DEGRADED)
        ));
        AdminIntegrationsService service = new AdminIntegrationsService(registry);

        IntegrationsOverviewDto overview = service.overview();

        assertThat(overview.integrations()).hasSize(1);
        assertThat(overview.integrations().get(0).name()).isEqualTo("Gmail Sync");
        assertThat(overview.integrations().get(0).status()).isEqualTo("DEGRADED");
        assertThat(overview.integrations().get(0).description()).isNotBlank();
    }

    @Test
    void overview_alwaysListsTheUpcomingPaymentProvider_regardlessOfRegisteredProviders() {
        AdminIntegrationsService service = new AdminIntegrationsService(new AdminHealthRegistryService(List.of()));

        IntegrationsOverviewDto overview = service.overview();

        assertThat(overview.integrations()).isEmpty();
        assertThat(overview.upcoming()).hasSize(1);
        assertThat(overview.upcoming().get(0).name()).contains("Payment Provider");
    }
}
