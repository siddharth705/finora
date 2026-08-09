package com.finora.config;

import org.junit.jupiter.api.Test;

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
}
