package com.finora.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bug 24 (docs/quality/bug-reports/BUG_REVIEW_REPORT.md). Was previously only exercised
 * indirectly through RateLimitFilterTest's own X-Forwarded-For cases, which only ever covered the
 * single-trusted-hop shape -- exactly the shape that couldn't tell "always take the last entry"
 * apart from "take the last trustedProxyHops entries, which happens to be 1." This file covers
 * ClientIpResolver directly, including the multi-hop case that distinguishes the two.
 */
class ClientIpResolverTest {

    private ClientIpResolver resolverWith(boolean trustProxyHeaders, int trustedProxyHops) {
        ClientIpResolver resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "trustProxyHeaders", trustProxyHeaders);
        ReflectionTestUtils.setField(resolver, "trustedProxyHops", trustedProxyHops);
        return resolver;
    }

    private HttpServletRequest requestWith(String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }

    @Test
    void resolve_usesRemoteAddr_whenProxyHeadersAreNotTrusted() {
        ClientIpResolver resolver = resolverWith(false, 1);
        HttpServletRequest request = requestWith("10.0.0.1", "1.2.3.4");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_takesTheLastHop_forTheDefaultSingleTrustedProxy() {
        // Finora's actual current deployment: one trusted hop (Railway's own edge proxy).
        ClientIpResolver resolver = resolverWith(true, 1);
        HttpServletRequest request = requestWith("10.0.0.1", "203.0.113.9, 198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    /**
     * The actual bug: with two trusted hops in front of the app, the LAST entry is the address
     * the INNER proxy saw (itself another proxy, not the real client) -- the real client's own IP
     * is the second-to-last entry, the one the OUTER (first) trusted proxy appended.
     */
    @Test
    void resolve_takesTheSecondToLastHop_whenTwoProxiesAreTrusted() {
        ClientIpResolver resolver = resolverWith(true, 2);
        HttpServletRequest request = requestWith("10.0.0.1", "203.0.113.9, 198.51.100.7, 10.0.0.5");

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.7");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenFewerHopsThanTrustedProxiesArePresent() {
        // Misconfiguration or a malformed/truncated header -- there is no entry guaranteed to be
        // proxy-appended, so this must not guess at an out-of-bounds index.
        ClientIpResolver resolver = resolverWith(true, 3);
        HttpServletRequest request = requestWith("10.0.0.1", "203.0.113.9, 198.51.100.7");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }

    @Test
    void resolve_fallsBackToRemoteAddr_whenHeaderIsAbsent() {
        ClientIpResolver resolver = resolverWith(true, 1);
        HttpServletRequest request = requestWith("10.0.0.1", null);

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.1");
    }
}
