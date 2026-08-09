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
    // BH-030. The sweep was "every SWEEP_INTERVAL calls", and callCount is per RateLimiter
    // INSTANCE. loginLimiter and registerLimiter see plenty of traffic and swept fine;
    // resetPasswordLimiter and passwordChangeLimiter are unlikely to see a thousand calls in a
    // deployment's lifetime, so their maps accumulated an entry per distinct IP and never shrank.
    // The fix comment claimed the leak was closed; it was closed for the busy limiters only.
    //
    // Elapsed time is the right trigger because it is what expiry is measured in. A sweep is cheap
    // (one removeIf over a small map) and a limiter that is never called does not need one -- this
    // runs at most once per interval, on a call, so an idle limiter costs nothing and a busy one
    // sweeps on a predictable clock rather than a traffic-dependent one.
    static final long DEFAULT_SWEEP_INTERVAL_SECONDS = 300;

    private final long sweepIntervalSeconds;
    private final AtomicLong lastSweepEpochSeconds = new AtomicLong(Instant.now().getEpochSecond());

    public RateLimiter(int maxRequests, long windowSeconds) {
        this(maxRequests, windowSeconds, DEFAULT_SWEEP_INTERVAL_SECONDS);
    }

    /**
     * Test seam, matching the one {@code RateLimitFilter} already carries for the same reason: the
     * shipped sweep interval is five minutes, and a test that waited for it would either not run or
     * not be a test. Package-private so only this package can shorten it.
     */
    RateLimiter(int maxRequests, long windowSeconds, long sweepIntervalSeconds) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.sweepIntervalSeconds = sweepIntervalSeconds;
    }

    /** How many client keys are currently being tracked. Exists so the eviction below can be
     *  asserted on directly -- a leak is invisible from the outside otherwise, which is exactly
     *  how BH-030 survived a fix that claimed to close it. */
    int trackedKeys() {
        return windows.size();
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit. */
    public boolean allow(String key) {
        long now = Instant.now().getEpochSecond();
        // compareAndSet so concurrent callers cannot both sweep: the loser sees the updated
        // timestamp and skips. Cheap enough that a missed sweep costs nothing anyway -- the next
        // call past the interval takes it.
        long lastSweep = lastSweepEpochSeconds.get();
        if (now - lastSweep >= sweepIntervalSeconds
                && lastSweepEpochSeconds.compareAndSet(lastSweep, now)) {
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
