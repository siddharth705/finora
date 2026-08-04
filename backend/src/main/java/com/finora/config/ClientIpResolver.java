package com.finora.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP for a request. The single implementation: RefreshTokenService uses
 * it to capture device-session metadata (see RefreshToken.lastSeenIp) and RateLimitFilter uses it
 * to bucket rate limits.
 *
 * RateLimitFilter used to carry a byte-identical private copy, and this comment used to end "keep
 * the two in sync if this logic ever changes" -- an instruction standing in for a mechanism, on a
 * security control where drift has two bad directions: collapsing every user onto one rate-limit
 * bucket (the proxy's own IP), or letting anyone spoof any IP and bypass the limiter entirely. The
 * stated reason for the duplication was not wanting to disturb that filter's already-tested
 * internals; both it and its tests were reworked in 6ee925a, so the reason expired and the copy
 * went with it.
 *
 * request.getRemoteAddr() alone only returns the real client IP when nothing sits between the
 * client and this app -- behind Railway (or any reverse proxy), every request arrives from the
 * proxy's own IP. X-Forwarded-For is only trusted when app.security.trust-proxy-headers is
 * enabled, and even then only its LAST entry (the hop the trusted proxy itself appended) -- a
 * reverse proxy APPENDS to whatever X-Forwarded-For it received from upstream, so every entry
 * before the last one is fully attacker-controlled. See RateLimitFilter's own original doc
 * comment (this class's history) for the full incident this specific detail fixes.
 */
@Component
public class ClientIpResolver {

    @Value("${app.security.trust-proxy-headers:false}")
    private boolean trustProxyHeaders;

    public String resolve(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwardedFor = request.getHeader("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                String[] hops = forwardedFor.split(",");
                return hops[hops.length - 1].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
