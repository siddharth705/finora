package com.finora.security;

import com.finora.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Previously had no dedicated coverage at all. Bug 53
 * (docs/quality/bug-reports/BUG_REVIEW_REPORT.md) is the reason SameSite is now a constructor
 * parameter rather than the hardcoded "Lax" literal it used to be -- see this file's own tests for
 * that, plus a lock-in of the other security-relevant attributes (HttpOnly, Secure, host-only, the
 * scoped path) and the cookie-over-body resolve() precedence the class's own doc comment states.
 */
class RefreshTokenCookieTest {

    private JwtProperties jwtProperties() {
        JwtProperties props = new JwtProperties();
        props.setRefreshExpirationMs(30L * 24 * 60 * 60 * 1000);
        return props;
    }

    @Test
    void issue_defaultsSameSiteToLax_matchingTheCurrentSameRegistrableDomainDeployment() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.issue("a-refresh-token");

        assertThat(result.getSameSite()).isEqualTo("Lax");
    }

    /** Bug 53's actual fix: SameSite is configurable rather than hardcoded, so a deployment where
     *  the SPA and API no longer share a registrable domain can switch to None without a code
     *  change. */
    @Test
    void issue_honorsAConfiguredSameSiteValue() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "None");

        ResponseCookie result = cookie.issue("a-refresh-token");

        assertThat(result.getSameSite()).isEqualTo("None");
    }

    @Test
    void issue_setsHttpOnlyAndSecure_andNoDomainAttribute() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.issue("a-refresh-token");

        assertThat(result.isHttpOnly()).isTrue();
        assertThat(result.isSecure()).isTrue();
        // Host-only, deliberately -- see the class's own doc comment on why no Domain attribute.
        assertThat(result.getDomain()).isNull();
        assertThat(result.getPath()).isEqualTo("/api/v1/auth");
    }

    @Test
    void clear_expiresImmediately_withEveryOtherAttributeMatchingIssue() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");

        ResponseCookie result = cookie.clear();

        assertThat(result.getMaxAge().getSeconds()).isZero();
        assertThat(result.getPath()).isEqualTo("/api/v1/auth");
        assertThat(result.getSameSite()).isEqualTo("Lax");
    }

    @Test
    void resolve_prefersTheCookieOverTheBodyToken_whenBothArePresent() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(new Cookie[]{new Cookie(RefreshTokenCookie.NAME, "from-cookie")});

        var resolved = cookie.resolve(request, "from-body");

        assertThat(resolved).contains("from-cookie");
    }

    @Test
    void resolve_fallsBackToTheBodyToken_whenNoCookieIsPresent() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        var resolved = cookie.resolve(request, "from-body");

        assertThat(resolved).contains("from-body");
    }

    @Test
    void resolve_isEmpty_whenNeitherTransportSuppliesAToken() {
        RefreshTokenCookie cookie = new RefreshTokenCookie(jwtProperties(), "Lax");
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getCookies()).thenReturn(null);

        var resolved = cookie.resolve(request, null);

        assertThat(resolved).isEmpty();
    }
}
