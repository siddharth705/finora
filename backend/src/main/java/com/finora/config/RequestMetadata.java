package com.finora.config;

import com.finora.util.UserAgentParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Best-effort ip/browser/device labels for the live request, for callers that want them for an
 * audit entry or a security-notification email rather than a persisted RefreshToken row (see that
 * entity's own capture in RefreshTokenService, which predates this and stays as-is -- this exists
 * so a second and third caller, UserAccountLifecycleService and AuthService.reactivate(), don't
 * each re-derive the identical try/catch-per-field pattern RefreshTokenService already has).
 *
 * Every accessor is a best-effort lookup, never a throw: a test harness or any caller running
 * outside a real HTTP request context gets null back, not a failure of the action this metadata is
 * only ever decorating.
 */
@Component
public class RequestMetadata {

    private final HttpServletRequest request;
    private final ClientIpResolver clientIpResolver;

    public RequestMetadata(HttpServletRequest request, ClientIpResolver clientIpResolver) {
        this.request = request;
        this.clientIpResolver = clientIpResolver;
    }

    public String ip() {
        try {
            return clientIpResolver.resolve(request);
        } catch (Exception e) {
            return null;
        }
    }

    public String browser() {
        try {
            return UserAgentParser.browser(request.getHeader("User-Agent"));
        } catch (Exception e) {
            return null;
        }
    }

    public String device() {
        try {
            return UserAgentParser.device(request.getHeader("User-Agent"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Adds "ip"/"device" to an audit-metadata map, each omitted (not a placeholder value) when
     *  unavailable -- the same null-guarded put every caller building an audit entry from this
     *  class's fields would otherwise repeat individually. Mutates and returns {@code metadata} so
     *  it composes with a caller that's already populated other keys first. */
    public Map<String, Object> addTo(Map<String, Object> metadata) {
        String ip = ip();
        String device = device();
        if (ip != null) metadata.put("ip", ip);
        if (device != null) metadata.put("device", device);
        return metadata;
    }
}
