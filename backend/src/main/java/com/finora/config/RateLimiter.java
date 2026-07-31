package com.finora.config;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fixed-window rate limiter, in-memory, scoped to a single instance. This is honestly the
 * right amount of engineering for a single-instance deployment — a proper sliding-window
 * limiter backed by Redis (so it works correctly across multiple instances) is the natural
 * upgrade path once there's more than one instance running, but that's premature to build
 * before there's a second instance to synchronize across.
 *
 * Fixed-window has a known edge case (a client can send 2x the limit across a window boundary
 * by timing requests just before/after the reset) — acceptable here because the goal is
 * blunting automated abuse (credential stuffing, registration spam), not precise quota
 * enforcement.
 */
public class RateLimiter {

    private record Window(long windowStartEpochSeconds, AtomicInteger count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowSeconds;

    public RateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit. */
    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartEpochSeconds() >= windowSeconds) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return window.count().get() <= maxRequests;
    }
}
