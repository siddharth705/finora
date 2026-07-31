package com.finora.health;

/** detail is always a short, human-readable sentence explaining WHY status is what it is (e.g.
 *  "3 of 3 Actuator indicators UP", "12 imports in the last 24h, 0 with skipped rows") -- an
 *  admin looking at "DEGRADED" with no explanation is worse than not having the tile at all. */
public record HealthCheckResult(HealthStatus status, String detail) {
    public static HealthCheckResult up(String detail) {
        return new HealthCheckResult(HealthStatus.UP, detail);
    }
    public static HealthCheckResult degraded(String detail) {
        return new HealthCheckResult(HealthStatus.DEGRADED, detail);
    }
    public static HealthCheckResult down(String detail) {
        return new HealthCheckResult(HealthStatus.DOWN, detail);
    }
}
