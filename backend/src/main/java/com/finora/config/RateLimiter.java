package com.finora.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A sliding-window (log-based) rate limiter, in-memory, scoped to a single instance. This is
 * honestly the right amount of engineering for a single-instance deployment — a Redis-backed
 * version (so it works correctly across multiple instances) is the natural upgrade path once
 * there's more than one instance running, but that's premature to build before there's a second
 * instance to synchronize across.
 *
 * <p>Each key's log is bounded by {@code maxRequests} entries: a rejected request is never
 * recorded (there's nothing new to remember about it), so a client hammering an endpoint past its
 * limit costs one map read per call, not an ever-growing log.
 */
public class RateLimiter {

    private final ConcurrentHashMap<String, List<Long>> requestLogs = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowSeconds;
    private final Clock clock;

    // Bug fix: `requestLogs` had no eviction at all -- one entry accumulates per distinct client
    // IP that ever calls allow() and is never removed, even long after that IP's log has fully
    // expired. /auth/login and /auth/register are public and routinely hit by bots/scanners, so on
    // a long-running single instance (Railway restarts on failure, not routinely) this grows
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
    // (one pass over a small map) and a limiter that is never called does not need one -- this
    // runs at most once per interval, on a call, so an idle limiter costs nothing and a busy one
    // sweeps on a predictable clock rather than a traffic-dependent one.
    static final long DEFAULT_SWEEP_INTERVAL_SECONDS = 300;

    private final long sweepIntervalSeconds;
    private final AtomicLong lastSweepEpochSeconds;

    public RateLimiter(int maxRequests, long windowSeconds) {
        this(maxRequests, windowSeconds, DEFAULT_SWEEP_INTERVAL_SECONDS);
    }

    /**
     * Test seam, matching the one {@code RateLimitFilter} already carries for the same reason: the
     * shipped sweep interval is five minutes, and a test that waited for it would either not run or
     * not be a test. Package-private so only this package can shorten it.
     */
    RateLimiter(int maxRequests, long windowSeconds, long sweepIntervalSeconds) {
        this(maxRequests, windowSeconds, sweepIntervalSeconds, Clock.systemUTC());
    }

    /**
     * Test seam: an injectable clock lets a test control elapsed time exactly (advance to one
     * second before a boundary, then past it) without a real {@code Thread.sleep()}. Package-
     * private for the same reason as the sweep-interval seam above.
     */
    RateLimiter(int maxRequests, long windowSeconds, long sweepIntervalSeconds, Clock clock) {
        this.maxRequests = maxRequests;
        this.windowSeconds = windowSeconds;
        this.sweepIntervalSeconds = sweepIntervalSeconds;
        this.clock = clock;
        this.lastSweepEpochSeconds = new AtomicLong(clock.instant().getEpochSecond());
    }

    /** How many client keys are currently being tracked. Exists so the eviction below can be
     *  asserted on directly -- a leak is invisible from the outside otherwise, which is exactly
     *  how BH-030 survived a fix that claimed to close it. */
    int trackedKeys() {
        return requestLogs.size();
    }

    /** Returns true if the request is allowed, false if the caller has exceeded the limit.
     *
     *  <p>Strict expiry: a timestamp is retained while {@code now - timestamp < windowSeconds};
     *  an entry exactly one full window old has expired. This is what actually closes the
     *  fixed-window boundary-burst gap the old implementation had -- each request is judged
     *  against the requests truly still within the trailing {@code windowSeconds}, not against
     *  whichever arbitrary bucket happened to contain it. */
    public boolean allow(String key) {
        long now = clock.instant().getEpochSecond();
        // compareAndSet so concurrent callers cannot both sweep: the loser sees the updated
        // timestamp and skips. Cheap enough that a missed sweep costs nothing anyway -- the next
        // call past the interval takes it.
        long lastSweep = lastSweepEpochSeconds.get();
        if (now - lastSweep >= sweepIntervalSeconds
                && lastSweepEpochSeconds.compareAndSet(lastSweep, now)) {
            evictExpired(now);
        }
        // Evict + check + append all run inside compute()'s lambda, which ConcurrentHashMap holds
        // the per-key lock for -- the same discipline the old fixed-window code used (see its own
        // history, Bug 25) to stop a concurrent caller for the same key from squeezing its own
        // update into the gap between this call's computation and a separate read-back. Each call
        // replaces the key's log with a brand-new immutable List rather than mutating a shared
        // collection in place, so evictExpired() below -- which reads log contents without holding
        // this per-key lock -- only ever observes a complete, consistent snapshot, never a
        // partially-mutated one.
        boolean[] allowed = new boolean[1];
        requestLogs.compute(key, (k, existing) -> {
            if (existing == null) {
                allowed[0] = true;
                return List.of(now);
            }
            List<Long> unexpired = new ArrayList<>(existing.size() + 1);
            for (long timestamp : existing) {
                if (now - timestamp < windowSeconds) {
                    unexpired.add(timestamp);
                }
            }
            if (unexpired.size() >= maxRequests) {
                allowed[0] = false;
                // Unchanged, not the trimmed view -- there is nothing new to persist about a
                // rejected request, and skipping the write means a client hammering the endpoint
                // past its limit doesn't force a map write on every single call. The next allow()
                // for this key re-filters from a fresh `now` regardless of what's stored here.
                return existing;
            }
            allowed[0] = true;
            unexpired.add(now);
            return List.copyOf(unexpired);
        });
        return allowed[0];
    }

    /** Safe to run concurrently with allow(): each key's log is an immutable List that allow()
     *  atomically replaces via compute(), so a log observed here is always a complete, consistent
     *  snapshot -- never a partially-mutated collection. Removal is conditional
     *  ({@code requestLogs.remove(key, observedLog)}, which only removes if that exact List
     *  reference/value is still the current mapping) rather than an unconditional
     *  {@code entrySet().removeIf(...)}, specifically so a concurrent allow() that just refreshed
     *  this key (appended a new timestamp) between this method reading the log and deciding to
     *  evict it can't have that fresh entry deleted out from under it. */
    private void evictExpired(long now) {
        for (Map.Entry<String, List<Long>> entry : requestLogs.entrySet()) {
            List<Long> log = entry.getValue();
            // Entries are only ever appended in non-decreasing clock order, so the last element is
            // always the newest -- a log is fully expired exactly when even its newest entry is.
            Long newest = log.isEmpty() ? null : log.get(log.size() - 1);
            if (newest == null || now - newest >= windowSeconds) {
                requestLogs.remove(entry.getKey(), log);
            }
        }
    }
}
