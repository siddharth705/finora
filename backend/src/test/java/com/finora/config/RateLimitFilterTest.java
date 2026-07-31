package com.finora.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Covers the production-readiness fix to client IP resolution: request.getRemoteAddr() alone
 * returns Railway's own edge-proxy IP for every request once actually deployed there, collapsing
 * every user onto one shared rate-limit bucket. Locks in that X-Forwarded-For is only trusted
 * when explicitly enabled (app.security.trust-proxy-headers / TRUST_PROXY_HEADERS), never
 * unconditionally -- trusting a client-supplied header with no real proxy in front to have set it
 * would let anyone spoof any IP and bypass rate limiting entirely.
 */
class RateLimitFilterTest {

    private HttpServletRequest requestFor(String path, String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }

    /** Drives the same request through the filter enough times to exceed the login limiter's
     *  10/min bucket for whatever IP the filter actually resolves -- the resulting 429 (or lack
     *  of one) is the observable proof of which IP was used, since resolveClientIp() itself is
     *  private. */
    private boolean tripsRateLimitAfterManyRequests(RateLimitFilter filter, HttpServletRequest request) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        boolean any429 = false;
        for (int i = 0; i < 15; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            if (response.getStatus() == 429) any429 = true;
        }
        return any429;
    }

    @Test
    void resolvesToRemoteAddr_whenProxyHeadersAreNotTrusted_evenIfForwardedForIsPresent() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "trustProxyHeaders", false);

        // Two different callers, both claiming (via a spoofable header) to be the same
        // "trusted-looking" IP, but with genuinely different getRemoteAddr() values -- with
        // trust disabled, each must be rate-limited independently by its own real remote address.
        HttpServletRequest attacker = requestFor("/api/v1/auth/login", "10.0.0.1", "1.2.3.4");
        HttpServletRequest victim = requestFor("/api/v1/auth/login", "10.0.0.2", "1.2.3.4");

        assertThat(tripsRateLimitAfterManyRequests(filter, attacker)).isTrue();
        // The victim's own real IP (10.0.0.2) has made zero requests of its own yet, so it must
        // not already be rate-limited just because the attacker also sent "1.2.3.4".
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(victim, response, chain);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void resolvesToForwardedForsFirstEntry_whenProxyHeadersAreTrusted() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "trustProxyHeaders", true);

        // Every request "arrives from" the same proxy IP (getRemoteAddr()), the way it actually
        // would behind Railway's edge proxy -- but each carries a DIFFERENT real client IP as the
        // first X-Forwarded-For entry. With trust enabled, these must be rate-limited
        // independently by that real client IP, not collapsed onto the shared proxy IP.
        HttpServletRequest clientA = requestFor("/api/v1/auth/login", "10.0.0.1", "203.0.113.1, 10.0.0.1");
        HttpServletRequest clientB = requestFor("/api/v1/auth/login", "10.0.0.1", "203.0.113.2, 10.0.0.1");

        assertThat(tripsRateLimitAfterManyRequests(filter, clientA)).isTrue();

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(clientB, response, chain);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void fallsBackToRemoteAddr_whenTrustedButForwardedForIsMissing() throws Exception {
        RateLimitFilter filter = new RateLimitFilter();
        ReflectionTestUtils.setField(filter, "trustProxyHeaders", true);

        HttpServletRequest request = requestFor("/api/v1/auth/login", "10.0.0.5", null);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Must not throw / must not resolve to a null IP just because trust is on but the header
        // never showed up on this particular request.
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain, times(1)).doFilter(any(), any());
    }
}
