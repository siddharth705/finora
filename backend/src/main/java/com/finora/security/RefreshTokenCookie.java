package com.finora.security;

import com.finora.config.JwtProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

/**
 * The refresh token as an {@code HttpOnly} cookie, and the one place that decides which transport
 * a request used.
 *
 * <h2>Why a cookie at all</h2>
 * The refresh token is the durable credential — good for up to the absolute session cap, where an
 * access token is good for fifteen minutes. In {@code localStorage} it is readable by any script
 * that manages to run on the page; as {@code HttpOnly} it is not readable by script at all, which
 * is the single largest reduction in blast radius available for an XSS on a page that shows bank
 * statements.
 *
 * <h2>Host-only, deliberately</h2>
 * No {@code Domain} attribute. Setting {@code Domain=.finoratech.info} would send this credential
 * to every current and future subdomain — marketing pages, status pages, anything — when the only
 * thing that ever needs it is the API host that issued it. Omitting {@code Domain} makes the
 * cookie host-only, which is least privilege and costs nothing: the frontend never reads it, so
 * it gains nothing from being shared.
 *
 * <h2>Why {@code SameSite=Lax} is enough</h2>
 * {@code app.finoratech.info} and {@code api.finoratech.info} share the registrable domain
 * {@code finoratech.info}, so a request from one to the other is same-SITE even though it is
 * cross-ORIGIN. Lax cookies are sent on same-site subresource requests. That matters beyond
 * tidiness: the alternative, {@code SameSite=None}, is what browsers are progressively restricting
 * as third-party cookies, and a credential that depends on it has a deprecation clock attached.
 * This only became available when the API moved onto the same registrable domain.
 *
 * <h2>Path</h2>
 * Scoped to {@code /api/v1/auth}. Every other endpoint authenticates with the access token and has
 * no use for this cookie, so there is no reason for the browser to attach it to them.
 */
@Component
public class RefreshTokenCookie {

    public static final String NAME = "finora_refresh_token";

    /** Only the auth endpoints exchange or clear a refresh token; nothing else needs it attached. */
    private static final String PATH = "/api/v1/auth";

    private final JwtProperties jwtProperties;

    public RefreshTokenCookie(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    /**
     * The refresh token this request supplied, preferring the cookie.
     *
     * <p>Precedence is cookie, then body, then absent. Both transports stay supported permanently
     * rather than the body being removed once web migrates: mobile is a native client with no
     * cookie jar and will always send a body, and integration tests are easier to write against
     * one. Branching the endpoint instead would duplicate rotation, reuse detection and the
     * session limits across two paths that must never disagree.
     *
     * <p>Cookie wins when both are present because it is the transport the browser cannot be
     * tricked into forging by script. In practice they carry the same value: every path that
     * issues a token writes both.
     */
    public Optional<String> resolve(HttpServletRequest request, String bodyToken) {
        Optional<String> fromCookie = request.getCookies() == null
                ? Optional.empty()
                : Arrays.stream(request.getCookies())
                        .filter(c -> NAME.equals(c.getName()))
                        .map(jakarta.servlet.http.Cookie::getValue)
                        .filter(v -> v != null && !v.isBlank())
                        .findFirst();
        if (fromCookie.isPresent()) {
            return fromCookie;
        }
        return Optional.ofNullable(bodyToken).filter(t -> !t.isBlank());
    }

    /** Set-Cookie carrying a freshly issued or rotated refresh token. */
    public ResponseCookie issue(String rawToken) {
        return base(rawToken)
                .maxAge(Duration.ofMillis(jwtProperties.getRefreshExpirationMs()))
                .build();
    }

    /**
     * Set-Cookie that removes it. Every attribute except {@code Max-Age} must match the original
     * or the browser treats it as a different cookie and quietly keeps the old one — a logout that
     * appears to work and leaves the credential in place.
     */
    public ResponseCookie clear() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(PATH);
    }
}
