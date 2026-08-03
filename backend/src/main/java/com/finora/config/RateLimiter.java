package com.finora.config;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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

    // Bug fix: `windows` had no eviction at all -- one entry accumulates per distinct client IP
    // that ever calls allow() and is never removed, even long after that IP's window has expired.
    // /auth/login and /auth/register are public and routinely hit by bots/scanners, so on a
    // long-running single instance (Railway restarts on failure, not routinely) this grows
    // without bound -- a slow memory-exhaustion vector needing no authentication to trigger.
    // Piggybacks a sweep onto allow() itself every SWEEP_INTERVAL calls, rather than adding a
    // @Scheduled task -- this codebase has no async/scheduled infrastructure anywhere else, and a
    // sweep this small doesn't earn introducing that as a new pattern just for this.
    private static final long SWEEP_INTERVAL = 1000;
    private final AtomicLong callCount = new AtomicLong();

    public RateLimiter(int maxRequests, long windowSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit. */
    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        if (callCount.incrementAndGet() % SWEEP_INTERVAL == 0) {
            evictExpired(now);
        }
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartEpochSeconds() >= windowSeconds) {
                return new Window(now, new AtomicInteger(1));
            }
            existing.count().incrementAndGet();
            return existing;
        });
        return window.count().get() <= maxRequests;
    }

    /** Safe to run concurrently with allow() -- ConcurrentHashMap's entrySet().removeIf() uses a
     *  weakly-consistent iterator, so this never throws ConcurrentModificationException and a
     *  window created by a concurrent allow() call is simply not visible to this pass yet
     *  (harmless -- it isn't expired anyway, since it was just created). */
    private void evictExpired(long now) {
        windows.entrySet().removeIf(e -> now - e.getValue().windowStartEpochSeconds() >= windowSeconds);
    }
}
