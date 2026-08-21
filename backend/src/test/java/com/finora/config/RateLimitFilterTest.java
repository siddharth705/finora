package com.finora.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.finora.config.RateLimitFilter.*;
import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * The ObjectMapper mirrors what Spring Boot's own JacksonAutoConfiguration gives the managed
     * bean (JavaTimeModule registered, among other things) -- a bare `new ObjectMapper()` here
     * would reproduce the exact bug this filter used to have.
     *
     * trust-proxy-headers is set on the injected ClientIpResolver rather than on the filter: the
     * filter no longer resolves IPs itself. That logic had been duplicated between the two, with a
     * comment asking whoever changed it to keep both copies in sync.
     */
    private RateLimitFilter newFilter(boolean trustProxyHeaders) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        ClientIpResolver clientIpResolver = new ClientIpResolver();
        ReflectionTestUtils.setField(clientIpResolver, "trustProxyHeaders", trustProxyHeaders);
        return new RateLimitFilter(objectMapper, clientIpResolver);
    }

    private HttpServletRequest requestFor(String path, String remoteAddr, String forwardedFor) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader("X-Forwarded-For")).thenReturn(forwardedFor);
        return request;
    }

    /** Drives the same request through the filter enough times to exceed whichever limiter applies
     *  for the IP the filter actually resolves -- the resulting 429 (or lack of one) is the
     *  observable proof of which IP and which endpoint were used, since limiterFor() is private
     *  and the IP resolution now happens inside the injected ClientIpResolver. The count must clear the most generous bucket in the class
     *  (passwordChangeLimiter, 15 per window), not just login's 10 -- at exactly 15 the 15th
     *  request is still allowed, which previously made this helper unable to trip it at all. */
    private boolean tripsRateLimitAfterManyRequests(RateLimitFilter filter, HttpServletRequest request) throws Exception {
        FilterChain chain = mock(FilterChain.class);
        boolean any429 = false;
        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            if (response.getStatus() == 429) any429 = true;
        }
        return any429;
    }

    @Test
    void resolvesToRemoteAddr_whenProxyHeadersAreNotTrusted_evenIfForwardedForIsPresent() throws Exception {
        RateLimitFilter filter = newFilter(false);

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
    void resolvesToForwardedForsLastEntry_whenProxyHeadersAreTrusted() throws Exception {
        RateLimitFilter filter = newFilter(true);

        // Every request "arrives from" the same proxy IP (getRemoteAddr()), the way it actually
        // would behind Railway's edge proxy -- a reverse proxy APPENDS the IP it observed to
        // whatever X-Forwarded-For it received from upstream, so with exactly one trusted proxy
        // in front of this app, the LAST entry is the one that proxy itself appended (trustworthy)
        // and everything before it is whatever the original request already carried (not). Each
        // request here carries a DIFFERENT real client IP as the LAST entry -- these must be
        // rate-limited independently by it, not collapsed onto the shared proxy IP.
        HttpServletRequest clientA = requestFor("/api/v1/auth/login", "10.0.0.1", "203.0.113.1");
        HttpServletRequest clientB = requestFor("/api/v1/auth/login", "10.0.0.1", "203.0.113.2");

        assertThat(tripsRateLimitAfterManyRequests(filter, clientA)).isTrue();

        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(clientB, response, chain);
        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    /**
     * Bug fix regression test: this used to take the FIRST X-Forwarded-For entry, on the
     * (backwards) theory that "the first entry is the original client." A request that includes
     * its own X-Forwarded-For header arrives here as "&lt;whatever the client sent&gt;, &lt;real
     * address the trusted proxy actually appended&gt;" -- taking the first entry let an attacker
     * bypass every rate limiter in this class by sending a fresh, different spoofed value on
     * every single request, since each one landed in its own bucket. Only the LAST entry (the
     * trusted proxy's own hop) may ever be trusted.
     */
    @Test
    void bugFix_ignoresAClientSuppliedLeadingHop_andUsesOnlyTheProxyAppendedLastEntry() throws Exception {
        RateLimitFilter filter = newFilter(true);
        FilterChain chain = mock(FilterChain.class);

        boolean tripped = false;
        for (int i = 0; i < 15; i++) {
            // A different spoofed leading hop on every request, but the SAME real address (the
            // one the trusted proxy actually appended) every time.
            HttpServletRequest request = requestFor("/api/v1/auth/login", "10.0.0.1",
                    "spoofed-" + i + ".attacker.example, 198.51.100.9");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            if (response.getStatus() == 429) tripped = true;
        }

        assertThat(tripped)
                .as("a fresh spoofed leading X-Forwarded-For hop on every request must not bypass the limiter")
                .isTrue();
    }

    @Test
    void fallsBackToRemoteAddr_whenTrustedButForwardedForIsMissing() throws Exception {
        RateLimitFilter filter = newFilter(true);

        HttpServletRequest request = requestFor("/api/v1/auth/login", "10.0.0.5", null);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Must not throw / must not resolve to a null IP just because trust is on but the header
        // never showed up on this particular request.
        filter.doFilterInternal(request, response, chain);
        assertThat(response.getStatus()).isNotEqualTo(429);
        verify(chain, times(1)).doFilter(any(), any());
    }

    /**
     * Bug fix regression test: which limiter applies used to be decided by a {@code switch} over
     * the RAW {@code request.getRequestURI()}. Spring routes on the DECODED path, so
     * {@code /api/v1/auth/%6Cogin} ({@code %6C} is {@code l}) reached AuthController.login() and
     * performed a real login attempt while this filter matched nothing and applied no limiter --
     * making every limiter here bypassable, with no credential required, by percent-encoding one
     * character. Every pre-existing test in this class kept passing because they all fed the
     * filter already-canonical paths.
     *
     * <p>Each encoded spelling below must land in the SAME bucket as the canonical path, not a
     * fresh unlimited one.
     */
    @Test
    void bugFix_percentEncodedSpellingsOfALimitedPathAreStillLimited() throws Exception {
        String[] encodedSpellings = {
                "/api/v1/auth/%6Cogin",              // l
                "/api/v1/auth/lo%67in",              // g
                "/api/v1/%61uth/login",              // a in a leading segment
                "/api/v1/users/me/password-change/%73tart",
                "/api/v1/import/csv/%73tage",
        };

        for (String path : encodedSpellings) {
            RateLimitFilter filter = newFilter(false);
            HttpServletRequest request = requestFor(path, "10.0.0.77", null);

            assertThat(tripsRateLimitAfterManyRequests(filter, request))
                    .as("%s must be rate-limited -- Spring routes it to the same handler as the "
                            + "canonical path, so this filter must see it as the same endpoint", path)
                    .isTrue();
        }
    }

    /** The encoded spelling must share the canonical path's bucket, not merely have a bucket of its
     *  own -- otherwise an attacker still gets a fresh full quota per distinct spelling, and there
     *  are effectively unlimited spellings. */
    @Test
    void bugFix_anEncodedSpellingSharesTheCanonicalPathsBucket() throws Exception {
        RateLimitFilter filter = newFilter(false);
        FilterChain chain = mock(FilterChain.class);

        // Exhaust the login limiter via the canonical path.
        assertThat(tripsRateLimitAfterManyRequests(filter, requestFor("/api/v1/auth/login", "10.0.0.88", null)))
                .isTrue();

        // The same IP, same endpoint, different spelling -- already spent, so still blocked.
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilterInternal(requestFor("/api/v1/auth/%6Cogin", "10.0.0.88", null), response, chain);
        assertThat(response.getStatus())
                .as("a percent-encoded spelling must not hand out a fresh quota")
                .isEqualTo(429);
    }

    /**
     * BH-011. Every endpoint that costs something to call must be in the table, and the table is
     * the only thing that decides.
     *
     * <p>{@code /api/v1/import/jobs} -- the ASYNCHRONOUS upload path, and the one the web app now
     * uploads through -- was not in it. The two synchronous staging endpoints beside it were, on a
     * justification that applies to this one with more force: every call writes an object to
     * storage AND a queue row, and the job it creates then writes an import_sessions row holding
     * the raw statement bytes. Content addressing dedupes identical uploads, so an abusive caller
     * varies one byte and every request becomes a new object that nothing ever deletes.
     *
     * <p>{@code /api/v1/auth/reset-password/phone} was the other gap, and it was reasoned about
     * explicitly and wrongly: this filter's own class comment dismissed it as "cheap, no-real-cost",
     * which is an argument about COST on an endpoint whose problem is DISCLOSURE -- it returns the
     * account's full phone number to any holder of a reset token.
     *
     * <p>Written as a table rather than another one-off case because the failure mode is an
     * omission, and a test that names the endpoints one at a time fails to catch the next one for
     * exactly the same reason the filter did.
     */
    @Test
    void everyEndpointWithARealPerCallCostIsLimited() throws Exception {
        String[] mustBeLimited = {
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/register",
                "/api/v1/auth/google",
                "/api/v1/auth/forgot-password",
                "/api/v1/auth/reset-password",
                "/api/v1/auth/reset-password/phone",
                "/api/v1/auth/verify-email",
                "/api/v1/import/csv/stage",
                "/api/v1/import/pdf/stage",
                "/api/v1/import/jobs",
                "/api/v1/users/me/password-change/start",
                "/api/v1/users/me/password-change/verify-otp",
                "/api/v1/users/me/password-change/complete",
                "/api/v1/users/me/phone-change/start",
                "/api/v1/users/me/phone-change/verify-otp",
                "/api/v1/users/me/phone-change/complete",
                "/api/v1/users/me/data-export",
                "/api/v1/users/me/account/deactivate",
                "/api/v1/auth/mfa/verify",
        };

        for (String path : mustBeLimited) {
            RateLimitFilter filter = newFilter(false);
            assertThat(tripsRateLimitAfterManyRequests(filter, requestFor(path, "10.0.1.5", null)))
                    .as("%s writes or discloses something per call and must be behind a limiter", path)
                    .isTrue();
        }
    }

    /** The flip side: matching must not become so loose that unrelated endpoints get swept into a
     *  limiter. A path that merely starts with a limited one is a different endpoint. */
    @Test
    void doesNotLimitUnrelatedPathsThatSharePrefix() throws Exception {
        RateLimitFilter filter = newFilter(false);

        assertThat(tripsRateLimitAfterManyRequests(filter, requestFor("/api/v1/auth/login/extra", "10.0.0.99", null)))
                .isFalse();
        assertThat(tripsRateLimitAfterManyRequests(filter, requestFor("/api/v1/accounts", "10.0.0.99", null)))
                .isFalse();
    }

    /** The patterns are written relative to the application, so a non-empty servlet context path
     *  must be stripped before matching rather than causing every limiter to silently miss. */
    @Test
    void matchesWhenDeployedUnderAContextPath() throws Exception {
        RateLimitFilter filter = newFilter(false);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/finora/api/v1/auth/login");
        when(request.getContextPath()).thenReturn("/finora");
        when(request.getRemoteAddr()).thenReturn("10.0.0.55");
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);

        assertThat(tripsRateLimitAfterManyRequests(filter, request)).isTrue();
    }

    /** The three Change Password steps share one limiter/bucket per IP -- tripping the limit via
     *  one path must also block the other two, proving they're bucketed together rather than each
     *  independently allowing a full quota (which would let an attacker get 3x the effective
     *  budget by rotating across start/verify-otp/complete). */
    @Test
    void passwordChangeSteps_shareOneRateLimitBucket() throws Exception {
        RateLimitFilter filter = newFilter(false);
        FilterChain chain = mock(FilterChain.class);

        boolean tripped = false;
        for (int i = 0; i < 20; i++) {
            String path = i % 2 == 0
                    ? "/api/v1/users/me/password-change/start"
                    : "/api/v1/users/me/password-change/verify-otp";
            HttpServletRequest request = requestFor(path, "10.0.0.9", null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            if (response.getStatus() == 429) tripped = true;
        }
        assertThat(tripped).isTrue();

        // The third step, never called yet, is still blocked -- same shared bucket, not a fresh
        // quota of its own.
        HttpServletRequest completeRequest = requestFor("/api/v1/users/me/password-change/complete", "10.0.0.9", null);
        MockHttpServletResponse completeResponse = new MockHttpServletResponse();
        filter.doFilterInternal(completeRequest, completeResponse, chain);
        assertThat(completeResponse.getStatus()).isEqualTo(429);
    }

    /** Same bucketing property as passwordChangeSteps_shareOneRateLimitBucket, for the Change Phone
     *  Number flow's own three steps. */
    @Test
    void phoneChangeSteps_shareOneRateLimitBucket() throws Exception {
        RateLimitFilter filter = newFilter(false);
        FilterChain chain = mock(FilterChain.class);

        boolean tripped = false;
        for (int i = 0; i < 20; i++) {
            String path = i % 2 == 0
                    ? "/api/v1/users/me/phone-change/start"
                    : "/api/v1/users/me/phone-change/verify-otp";
            HttpServletRequest request = requestFor(path, "10.0.0.10", null);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilterInternal(request, response, chain);
            if (response.getStatus() == 429) tripped = true;
        }
        assertThat(tripped).isTrue();

        HttpServletRequest completeRequest = requestFor("/api/v1/users/me/phone-change/complete", "10.0.0.10", null);
        MockHttpServletResponse completeResponse = new MockHttpServletResponse();
        filter.doFilterInternal(completeRequest, completeResponse, chain);
        assertThat(completeResponse.getStatus()).isEqualTo(429);
    }

    /**
     * Bug fix (self-review after PR #142): the data-export ceiling was raised from 3/day to 5/day
     * in two places (DEFAULT_DATA_EXPORT_MAX and application.yml's own default) but the
     * @Value fallback on the real, Spring-managed constructor below was missed -- a third copy of
     * the same literal, exactly the "written in two places, they drift" failure this class's own
     * javadoc already warns about. Harmless today only because application.yml always supplies
     * the property in the real app; a future context that constructs this bean without it (a
     * narrower test slice, a refactor, an ops change relying on env vars alone) would have
     * silently reverted to the 3/day ceiling this PR explicitly fixed. Reflects over every
     * {@code @Value}-annotated parameter on the full constructor and checks its SpEL fallback
     * against the corresponding DEFAULT_* constant, so any future drift on ANY of them -- not
     * just this one -- fails loudly here instead of silently in a deploy that happens to omit
     * application.yml.
     */
    @Test
    void everyValueAnnotationsFallbackDefault_matchesItsCorrespondingConstant() throws Exception {
        Map<String, Integer> expectedByProperty = Map.ofEntries(
                Map.entry("app.rate-limit.login.max", DEFAULT_LOGIN_MAX),
                Map.entry("app.rate-limit.login.window-seconds", DEFAULT_LOGIN_WINDOW),
                Map.entry("app.rate-limit.register.max", DEFAULT_REGISTER_MAX),
                Map.entry("app.rate-limit.register.window-seconds", DEFAULT_REGISTER_WINDOW),
                Map.entry("app.rate-limit.forgot-password.max", DEFAULT_FORGOT_MAX),
                Map.entry("app.rate-limit.forgot-password.window-seconds", DEFAULT_FORGOT_WINDOW),
                Map.entry("app.rate-limit.import-stage.max", DEFAULT_IMPORT_STAGE_MAX),
                Map.entry("app.rate-limit.import-stage.window-seconds", DEFAULT_IMPORT_STAGE_WINDOW),
                Map.entry("app.rate-limit.password-change.max", DEFAULT_PASSWORD_CHANGE_MAX),
                Map.entry("app.rate-limit.password-change.window-seconds", DEFAULT_PASSWORD_CHANGE_WINDOW),
                Map.entry("app.rate-limit.phone-change.max", DEFAULT_PHONE_CHANGE_MAX),
                Map.entry("app.rate-limit.phone-change.window-seconds", DEFAULT_PHONE_CHANGE_WINDOW),
                Map.entry("app.rate-limit.reset-password.max", DEFAULT_RESET_PASSWORD_MAX),
                Map.entry("app.rate-limit.reset-password.window-seconds", DEFAULT_RESET_PASSWORD_WINDOW),
                Map.entry("app.rate-limit.data-export.max", DEFAULT_DATA_EXPORT_MAX),
                Map.entry("app.rate-limit.data-export.window-seconds", DEFAULT_DATA_EXPORT_WINDOW),
                Map.entry("app.rate-limit.google.max", DEFAULT_GOOGLE_MAX),
                Map.entry("app.rate-limit.google.window-seconds", DEFAULT_GOOGLE_WINDOW),
                Map.entry("app.rate-limit.apple.max", DEFAULT_APPLE_MAX),
                Map.entry("app.rate-limit.apple.window-seconds", DEFAULT_APPLE_WINDOW),
                Map.entry("app.rate-limit.mfa-verify.max", DEFAULT_MFA_VERIFY_MAX),
                Map.entry("app.rate-limit.mfa-verify.window-seconds", DEFAULT_MFA_VERIFY_WINDOW),
                Map.entry("app.rate-limit.refresh.max", DEFAULT_REFRESH_MAX),
                Map.entry("app.rate-limit.refresh.window-seconds", DEFAULT_REFRESH_WINDOW));

        Constructor<?> springConstructor = Arrays.stream(RateLimitFilter.class.getDeclaredConstructors())
                .filter(c -> c.getParameterCount() > 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected the @Value-annotated constructor to still exist"));

        Map<String, Integer> actualByProperty = new HashMap<>();
        for (Parameter p : springConstructor.getParameters()) {
            Value value = p.getAnnotation(Value.class);
            if (value == null) continue;
            // e.g. "${app.rate-limit.data-export.max:5}" -> key "app.rate-limit.data-export.max", default 5
            String spel = value.value().replace("${", "").replace("}", "");
            int colon = spel.lastIndexOf(':');
            actualByProperty.put(spel.substring(0, colon), Integer.parseInt(spel.substring(colon + 1)));
        }

        assertThat(actualByProperty).containsExactlyInAnyOrderEntriesOf(expectedByProperty);
    }
}
