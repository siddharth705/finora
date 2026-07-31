package com.finora.dto;

import java.util.List;

/** DTOs for the Operational Dashboard's health/alerts surface -- see com.finora.health
 *  .HealthProvider's class comment for the extensibility pattern these are built on top of. */
public class HealthDtos {

    public record ProviderStatusDto(String name, String category, String status, String detail) {}

    /** overallStatus is the "worst status wins" rollup across every registered provider (DOWN if
     *  any provider is DOWN, else DEGRADED if any is DEGRADED, else UP) -- see
     *  AdminHealthRegistryService.overall() for the exact precedence. */
    public record PlatformHealthDto(String overallStatus, List<ProviderStatusDto> providers) {}

    /** One entry per non-UP provider -- the Operational Dashboard's alerts panel is derived
     *  entirely from the health registry, not a separate alerting subsystem, so there's exactly
     *  one source of truth for "is something wrong" (AdminHealthRegistryService), not two that
     *  could drift out of sync. */
    public record AlertDto(String severity, String title, String detail) {}
}
