package com.finora.dto;

import java.util.List;

/**
 * DTOs for the admin portal's Integrations page. Deliberately separate from HealthDtos:
 * ProviderStatusDto is the raw, generic health-registry signal (see HealthProvider's class
 * comment); IntegrationDto layers a curated, human-facing description on top of that SAME live
 * status for the subset of providers that are genuinely third-party integrations (Database,
 * Financial Intelligence Engine, and Statement Import are internal engine checks, not
 * integrations, and are deliberately excluded here -- they stay on the Operational Dashboard).
 *
 * upcoming is NOT derived from the health registry and carries no status -- there is nothing
 * running to check yet. Keeping it a separate, explicitly-labelled list (rather than a provider
 * reporting a fake "NOT_BUILT" status) avoids exactly the fabricated-metric problem HealthProvider's
 * own doc comment warns against.
 */
public class IntegrationsDto {

    public record IntegrationDto(String name, String category, String description, String status, String detail) {}

    public record UpcomingIntegrationDto(String name, String description) {}

    public record IntegrationsOverviewDto(List<IntegrationDto> integrations, List<UpcomingIntegrationDto> upcoming) {}
}
