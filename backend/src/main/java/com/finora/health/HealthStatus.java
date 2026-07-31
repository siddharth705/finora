package com.finora.health;

/** UP: operating normally. DEGRADED: operating, but a real signal worth an admin's attention
 *  (e.g. an elevated skip rate). DOWN: not operating / a real integrity failure detected. */
public enum HealthStatus {
    UP, DEGRADED, DOWN
}
