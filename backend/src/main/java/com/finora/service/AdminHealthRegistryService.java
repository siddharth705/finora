package com.finora.service;

import com.finora.dto.HealthDtos.PlatformHealthDto;
import com.finora.dto.HealthDtos.ProviderStatusDto;
import com.finora.health.HealthCheckResult;
import com.finora.health.HealthProvider;
import com.finora.health.HealthStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Collects every Spring bean implementing HealthProvider (just `List<HealthProvider>` --  no
 * manual registration anywhere) and rolls them up into one platform health view for the
 * Operational Dashboard. Adding observability for a new module means writing one new
 * @Component HealthProvider; this service, the dashboard DTO, and the alerts panel all pick it
 * up automatically. See HealthProvider's own class comment for why infrastructure that doesn't
 * exist in this codebase yet (Redis, a job queue, OCR, Gmail, AI, Mail, Scheduler) has no
 * provider today rather than a fabricated placeholder tile.
 */
@Service
public class AdminHealthRegistryService {

    private final List<HealthProvider> providers;

    public AdminHealthRegistryService(List<HealthProvider> providers) {
        this.providers = providers;
    }

    public PlatformHealthDto platformHealth() {
        List<ProviderStatusDto> statuses = providers.stream()
                .map(p -> {
                    HealthCheckResult result = p.check();
                    return new ProviderStatusDto(p.name(), p.category(), result.status().name(), result.detail());
                })
                .sorted(Comparator.comparing(ProviderStatusDto::category).thenComparing(ProviderStatusDto::name))
                .toList();

        return new PlatformHealthDto(overallStatus(statuses).name(), statuses);
    }

    private HealthStatus overallStatus(List<ProviderStatusDto> statuses) {
        boolean anyDown = statuses.stream().anyMatch(s -> s.status().equals(HealthStatus.DOWN.name()));
        if (anyDown) return HealthStatus.DOWN;
        boolean anyDegraded = statuses.stream().anyMatch(s -> s.status().equals(HealthStatus.DEGRADED.name()));
        return anyDegraded ? HealthStatus.DEGRADED : HealthStatus.UP;
    }
}
