package com.finora.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves the real client IP for a request -- used by RefreshTokenService to capture
 * device-session metadata (see RefreshToken.lastSeenIp). Deliberately the same spoofing-safe
 * logic as RateLimitFilter's own private resolveClientIp() (not extracted into a shared
 * dependency between them, to avoid touching that filter's own already-tested internals) -- keep
 * the two in sync if this logic ever changes.
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
