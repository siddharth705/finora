package com.finora.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allow_permitsRequestsUpToTheLimit() {
        RateLimiter limiter = new RateLimiter(3, 60);
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isTrue();
    }

    @Test
    void allow_rejectsRequestsBeyondTheLimit() {
        RateLimiter limiter = new RateLimiter(2, 60);
        assertThat(limiter.allow("client-b")).isTrue();
        assertThat(limiter.allow("client-b")).isTrue();
        assertThat(limiter.allow("client-b")).isFalse();
        assertThat(limiter.allow("client-b")).isFalse();
    }

    @Test
    void allow_tracksDistinctKeysIndependently() {
        RateLimiter limiter = new RateLimiter(1, 60);
        assertThat(limiter.allow("client-c")).isTrue();
        assertThat(limiter.allow("client-c")).isFalse();
        // A different key (e.g. a different IP) isn't affected by client-c's limit.
        assertThat(limiter.allow("client-d")).isTrue();
    }

    @Test
    void allow_resetsAfterWindowExpires() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(1, 1); // 1 request per 1-second window
        assertThat(limiter.allow("client-e")).isTrue();
        assertThat(limiter.allow("client-e")).isFalse();
        Thread.sleep(1100);
        assertThat(limiter.allow("client-e")).isTrue();
    }

    /**
     * Deterministic version of the sliding-window boundary, via the injectable-clock seam instead
     * of a real sleep(). Strict expiry rule under test: a timestamp is retained while
     * {@code now - timestamp < windowSeconds} -- so a batch of maxRequests all sent at t=0 must
     * still fully count against the limit at t=windowSeconds-1 (age windowSeconds-1, still
     * retained), and must have fully expired by t=windowSeconds (age windowSeconds exactly, which
     * the strict rule expires) -- freeing the limit back up.
     */
    @Test
    void allow_treatsABatchAsExpiredOnlyOnceItIsAFullWindowOld() {
        MutableClock clock = new MutableClock(Instant.ofEpochSecond(0));
        int maxRequests = 3;
        long windowSeconds = 10;
        RateLimiter limiter = new RateLimiter(maxRequests, windowSeconds, RateLimiter.DEFAULT_SWEEP_INTERVAL_SECONDS, clock);

        for (int i = 0; i < maxRequests; i++) {
            assertThat(limiter.allow("client-f")).isTrue();
        }

        clock.advanceTo(Instant.ofEpochSecond(windowSeconds - 1));
        assertThat(limiter.allow("client-f"))
                .as("the original batch is windowSeconds-1 old -- still within the rolling window")
                .isFalse();

        clock.advanceTo(Instant.ofEpochSecond(windowSeconds));
        assertThat(limiter.allow("client-f"))
                .as("the original batch is now exactly windowSeconds old -- expired under the strict rule")
                .isTrue();
    }

    /** Test-only seam for {@link RateLimiter}'s injectable-clock constructor -- lets a test move
     *  time to an exact boundary instead of sleeping past it. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advanceTo(Instant next) {
            this.instant = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("not needed by RateLimiter, which only reads instant()");
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * BH-030. The sweep used to fire every SWEEP_INTERVAL <b>calls</b>, and the counter is per
     * limiter instance. loginLimiter and registerLimiter see plenty of traffic and swept fine;
     * resetPasswordLimiter and passwordChangeLimiter are unlikely to see a thousand calls in a
     * deployment's lifetime, so their maps grew one entry per distinct client IP and never shrank.
     * The commit that added eviction said the leak was closed -- it was closed for the busy
     * limiters only, which is the kind of half-fix nothing observable distinguishes from a whole
     * one.
     *
     * <p>Asserted through {@code trackedKeys()} because that is the only way to see it: a leaking
     * limiter rate-limits exactly as correctly as a healthy one, so no behavioural assertion can
     * tell them apart.
     */
    @Test
    void expiredWindowsAreEvictedOnAQuietLimiter() throws InterruptedException {
        // One-second window, one-second sweep -- the shipped values are 300s, which is the whole
        // reason the interval is a test seam.
        RateLimiter limiter = new RateLimiter(5, 1, 1);

        for (int i = 0; i < 20; i++) {
            limiter.allow("192.0.2." + i);
        }
        assertThat(limiter.trackedKeys())
                .as("twenty distinct clients, twenty windows")
                .isEqualTo(20);

        Thread.sleep(1100);

        // Far fewer than the thousand calls the old counter-based trigger needed -- this is the
        // traffic a rarely-used limiter actually sees.
        limiter.allow("192.0.2.99");

        assertThat(limiter.trackedKeys())
                .as("the twenty expired windows are gone; only the caller that just arrived remains")
                .isEqualTo(1);
    }

    /** The flip side: a sweep must not evict a window that is still counting, or a client would
     *  get a fresh quota every time the sweep happened to run. */
    @Test
    void aLiveWindowSurvivesASweep() throws InterruptedException {
        RateLimiter limiter = new RateLimiter(2, 60, 1); // long window, short sweep

        assertThat(limiter.allow("192.0.2.1")).isTrue();
        assertThat(limiter.allow("192.0.2.1")).isTrue();

        Thread.sleep(1100);
        limiter.allow("192.0.2.2"); // triggers a sweep

        assertThat(limiter.allow("192.0.2.1"))
                .as("still inside its 60s window and already at the limit -- a sweep must not reset it")
                .isFalse();
    }

    /**
     * Bug 25. allow() used to read {@code window.count().get()} back *after* compute() had already
     * released the per-key lock, so a concurrent caller for the same key could squeeze its own
     * compute() (and its own increment) into the gap before this call's read -- the count this
     * request actually landed on was correct, but by the time it got read back it could reflect a
     * later caller's increment too, spuriously rejecting a request that was within limit at the
     * moment it was counted. The fix captures the post-increment count inside compute()'s lambda.
     *
     * <p>The bug could only ever cause fewer than maxRequests to be let through (a stale read is
     * always equal to or higher than the true value at increment time, never lower), so asserting
     * the allowed count is exactly maxRequests -- not merely "at most" -- is what would have caught
     * the regression.
     */
    @Test
    void allow_underConcurrentLoad_permitsExactlyMaxRequests() throws InterruptedException {
        int maxRequests = 20;
        int callers = 100;
        RateLimiter limiter = new RateLimiter(maxRequests, 60);

        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowedCount = new AtomicInteger();

        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (limiter.allow("contended-client")) {
                        allowedCount.incrementAndGet();
                    }
                });
            }
            ready.await();
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowedCount.get())
                .as("exactly the limit should be let through, no spurious rejections from a stale read")
                .isEqualTo(maxRequests);
    }
}
