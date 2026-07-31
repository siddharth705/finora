package com.finora.health;

/**
 * The extensibility seam for the Operational Dashboard's system-status panel. Every Spring bean
 * implementing this interface is automatically picked up by AdminHealthRegistryService (which
 * just injects `List<HealthProvider>` -- Spring collects every matching bean into that list with
 * no registration step required). Adding observability for a future module (Redis, a job queue,
 * OCR, Gmail sync, AI, Mail, Scheduler) means writing one new @Component implementing this
 * interface -- the dashboard, the aggregate "worst status wins" rollup, and the alerts panel
 * (AdminOperationalDashboardService) all pick it up automatically, with zero changes to any of
 * them. Deliberately NOT implemented for infrastructure that doesn't exist in this codebase yet
 * (no Redis, no background job runner, no OCR/Gmail/AI integration today) -- a health tile for a
 * system that isn't there would just be a fabricated metric, which defeats the point of this
 * panel. category groups related providers together in the UI (e.g. "Platform", "Financial
 * Intelligence") without requiring an enum every new module has to fit into.
 */
public interface HealthProvider {
    String name();
    String category();
    HealthCheckResult check();
}
