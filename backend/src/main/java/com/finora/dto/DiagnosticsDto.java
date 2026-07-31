package com.finora.dto;

import com.finora.dto.AdminDtos.RecentImportDto;
import com.finora.dto.HealthDtos.PlatformHealthDto;

import java.util.List;

/**
 * DTOs for Platform Diagnostics -- a lightweight, developer/support-facing page distinct from
 * both the Admin Dashboard (business/operational overview) and the existing System Health page
 * (Actuator-backed component checks). Every field here is either an existing real signal reused
 * as-is (health providers, recent imports) or a small, genuine addition (build/git metadata,
 * Flyway version, platform-wide counts) -- explicitly NOT a step toward log aggregation, metrics
 * storage, distributed tracing, or any other capability a dedicated observability tool (Grafana/
 * Loki/Prometheus/OpenTelemetry/Sentry) already does better. See the RFC this was built from
 * (recorded in chat, not yet its own ADR) for the reasoning and the explicit out-of-scope list.
 */
public class DiagnosticsDto {

    /**
     * gitCommit/buildTime are null when the app was started without the build-info/git-commit-id
     * Maven goals having run (e.g. launched straight from an IDE) -- see AdminDiagnosticsService
     * for why these are read via ObjectProvider rather than required beans, so a diagnostics page
     * missing this metadata is never a reason the app fails to start.
     */
    public record ApplicationInfoDto(
            String version,
            String gitCommit,
            String springProfile
    ) {}

    /**
     * cacheEnabled reflects whether a CacheManager bean actually exists, not a hardcoded value --
     * this app has none configured today (Hibernate's own startup log says as much: "Second-level
     * cache disabled"), so it reports false honestly rather than a value someone has to remember
     * to flip if caching is ever added later.
     */
    public record RuntimeInfoDto(
            long uptimeSeconds,
            String flywayVersion,
            boolean cacheEnabled
    ) {}

    /**
     * phoneVerificationPolicy is deliberately a fixed descriptive string, not a boolean toggle --
     * see ADR-0001: phone verification is hardcoded as mandatory today, with no policy layer, so
     * presenting it alongside genuinely configurable settings (registrationsEnabled) as if it
     * were one would misrepresent the current architecture.
     */
    public record ConfigurationSummaryDto(
            boolean registrationsEnabled,
            boolean setupCompleted,
            String phoneVerificationPolicy
    ) {}

    public record PlatformDiagnosticsDto(
            ApplicationInfoDto application,
            RuntimeInfoDto runtime,
            PlatformHealthDto health,
            ConfigurationSummaryDto configuration,
            List<RecentImportDto> recentImports
    ) {}
}
