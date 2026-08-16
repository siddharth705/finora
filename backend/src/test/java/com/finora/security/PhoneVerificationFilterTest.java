package com.finora.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Locks in backend enforcement of phone verification: a user with a valid, authenticated session
 * but an unverified phone must be rejected on every protected endpoint except the phone
 * verification endpoints themselves -- see the class doc comment on PhoneVerificationFilter for
 * why this exists (a valid JWT alone used to be enough to reach /app directly, bypassing
 * verification entirely).
 */
class PhoneVerificationFilterTest {

    private UserRepository userRepository;
    private PhoneVerificationFilter filter;
    private FilterChain filterChain;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        userRepository = mock(UserRepository.class);
        // Bug fix: a plain `new ObjectMapper()` has no java.time support at all -- production
        // never hits this, because Spring Boot's autoconfigured ObjectMapper bean (the one this
        // filter actually gets injected in the app) auto-registers JavaTimeModule whenever
        // jackson-datatype-jsr310 is on the classpath, which it always is here. This test built
        // its own bare ObjectMapper instead of mirroring that, so serializing ApiResponse's
        // Instant timestamp field (only exercised by the "blocked" response body, not the
        // "allowed" pass-through path below) threw InvalidDefinitionException.
        filter = new PhoneVerificationFilter(userRepository, new ObjectMapper().registerModule(new JavaTimeModule()));
        filterChain = mock(FilterChain.class);

        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest requestFor(String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getServletPath()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        return request;
    }

    /** The Spring Security principal is the user's ID, not their email (see
     *  CurrentUserDetailsService) -- an email identifies a user only within a portal scope since
     *  V52, so a principal keyed on one could resolve to the wrong account. These helpers derive a
     *  stable UUID per email so a test can still read in terms of who it is authenticating as. */
    private static UUID idFor(String email) {
        return UUID.nameUUIDFromBytes(email.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void authenticateAs(String email) {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername(idFor(email).toString()).password("irrelevant").authorities("ROLE_USER").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // userWithVerification(...) was removed along with the filter's full-entity load: the filter
    // now reads the phone-verified flag through a projection query rather than hydrating a User to
    // get at one boolean, so building a whole entity here would be constructing state the code
    // under test never looks at.

    @Test
    void blocksAnUnverifiedUser_fromAProtectedEndpoint() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        filter.doFilter(requestFor("/api/v1/accounts"), response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
        assertThat(responseBody.toString()).contains("PHONE_VERIFICATION_REQUIRED");
    }

    @Test
    void allowsAVerifiedUser_throughToTheProtectedEndpoint() throws Exception {
        authenticateAs("verified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("verified@example.com")))
                .thenReturn(Optional.of(true));

        HttpServletRequest request = requestFor("/api/v1/accounts");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void allowsAnUnverifiedUser_toReachThePhoneVerificationEndpointsThemselves() throws Exception {
        // Otherwise nobody could ever complete verification in the first place.
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/phone/verify-otp");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void passesThroughUnauthenticatedRequests_leavingThemToNormalAuthorization() throws Exception {
        // No authentication set at all -- e.g. a missing/invalid token, which JwtAuthFilter
        // already leaves unauthenticated. This filter must not interfere; Spring Security's own
        // anyRequest().authenticated() rule (and RestAuthenticationEntryPoint) handles it.
        HttpServletRequest request = requestFor("/api/v1/accounts");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(userRepository, never()).findPhoneVerifiedById(any());
    }

    /**
     * Regression test for a real bug: an unverified user's own /auth/logout call was being
     * blocked by this filter (it only excluded /api/v1/phone/**, not the whole /api/v1/auth/**
     * family SecurityConfig already permits), silently leaving their refresh token un-revoked
     * server-side while the frontend believed logout had succeeded.
     */
    @Test
    void allowsAnUnverifiedUser_toLogOut() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/auth/logout");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    void allowsAnUnverifiedUser_toRefreshTheirToken() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/auth/refresh");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * Regression test for the same class of bug allowsAnUnverifiedUser_toLogOut guards against:
     * GET /api/v1/setup/status is public at the SecurityConfig layer (the login page calls it
     * before anyone has a token), but this filter doesn't consult SecurityConfig's authorization
     * rules -- it only checks whatever Authentication happens to already be in the security
     * context. An unverified user whose session is still active would otherwise get incorrectly
     * blocked from a call meant to be reachable unconditionally.
     */
    @Test
    void allowsAnUnverifiedUser_toCheckSetupStatus() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/setup/status");
        when(request.getMethod()).thenReturn("GET");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * Regression test: both VerifyPhone.tsx pages (user app and admin portal) call
     * GET /api/v1/users/me as the very first step of their own verification flow, to learn the
     * account's real phone number before it can be handed to Firebase -- this filter blocked that
     * call too, so a brand-new registration's phone verification failed immediately and could
     * never be completed at all.
     */
    @Test
    void allowsAnUnverifiedUser_toFetchTheirOwnProfile() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/users/me");
        when(request.getMethod()).thenReturn("GET");
        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * The GET-only allowlist for /api/v1/users/me must not widen into letting an unverified user
     * change their own preferences (PUT, same base path) before ever verifying -- only the read
     * needed to bootstrap verification is excluded, nothing else on this resource.
     */
    @Test
    void stillBlocksAnUnverifiedUser_fromUpdatingTheirOwnProfile() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/users/me");
        when(request.getMethod()).thenReturn("PUT");
        filter.doFilter(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }

    /**
     * The deliberate exception under /api/v1/users/me/**: unlike password-change (see the next
     * test), phone-change is FOR unverified users -- it is the self-service recovery path reached
     * from VerifyPhone.tsx's own OTP-failure screen, for someone who cannot verify at all. Blocking
     * it here would make the feature unreachable by exactly the population it exists for.
     */
    @Test
    void allowsAnUnverifiedUser_toReachEveryPhoneChangeStep() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        for (String path : new String[] {
                "/api/v1/users/me/phone-change/start",
                "/api/v1/users/me/phone-change/verify-otp",
                "/api/v1/users/me/phone-change/complete",
        }) {
            HttpServletRequest request = requestFor(path);
            when(request.getMethod()).thenReturn("POST");
            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
        verify(response, never()).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    /**
     * The GET-only allowlist for /api/v1/users/me must not widen so far that password-change --
     * a flow that presumes an already-trusted, verified account -- becomes reachable too. Only
     * phone-change is the deliberate exception; this locks in that the two are not conflated.
     */
    @Test
    void stillBlocksAnUnverifiedUser_fromThePasswordChangeFlow() throws Exception {
        authenticateAs("unverified@example.com");
        when(userRepository.findPhoneVerifiedById(idFor("unverified@example.com")))
                .thenReturn(Optional.of(false));

        HttpServletRequest request = requestFor("/api/v1/users/me/password-change/start");
        when(request.getMethod()).thenReturn("POST");
        filter.doFilter(request, response, filterChain);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(filterChain, never()).doFilter(any(), any());
    }
}
