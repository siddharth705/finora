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
}
