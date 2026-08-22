package com.finora.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.RefreshTokenCookie;
import com.finora.service.RefreshTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /auth/refresh and /auth/logout accept the refresh token from a cookie OR a body, permanently.
 *
 * <p>Both transports are supported for good, not as a migration step. Mobile is a native client
 * with no cookie jar and will always send a body; browsers will send the cookie once they opt into
 * credentialed requests. Branching into two endpoints would duplicate rotation, reuse detection
 * and the session limits across paths that must never disagree, so instead the transport differs
 * and the authentication logic does not.
 *
 * <p>These tests exist to hold that contract long after the migration is forgotten. Every one of
 * them would pass against a body-only implementation before this change, or a cookie-only one
 * after it — which is exactly why both directions are asserted rather than just the new one.
 */
@AutoConfigureMockMvc
class RefreshTokenTransportIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    private String rawToken;
    private UUID userId;

    @BeforeEach
    void signIn() {
        User user = new User();
        user.setEmail("transport-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Transport Test");
        user = userRepository.save(user);
        userId = user.getId();
        rawToken = refreshTokenService.issue(userId).rawToken();
    }

    private String body(String token) throws Exception {
        return objectMapper.writeValueAsString(Map.of("refreshToken", token));
    }

    @Test
    void refreshAcceptsTheTokenInTheBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void refreshAcceptsTheTokenInACookieWithNoBodyAtAll() throws Exception {
        // No body: a browser sending only the cookie must not be rejected as a malformed request
        // before the token is even looked at. This is what @RequestBody(required = false) buys.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void theCookieWinsWhenBothAreSupplied() throws Exception {
        // The body carries a token that would be rejected. If the request still succeeds, the
        // cookie was used -- asserting the precedence rather than assuming it, since both-present
        // is the normal state for a browser during the migration.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("not-a-real-token")))
                .andExpect(status().isOk());
    }

    @Test
    void neitherTransportIsUnauthorizedRatherThanABadRequest() throws Exception {
        // 401, not 400. "You did not authenticate" is what the client's interceptor acts on; a 400
        // would read as a bug in the request shape and would not trigger the sign-out path.
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidCookieIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, "garbage")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBlankCookieFallsThroughToTheBodyRatherThanFailing() throws Exception {
        // A cleared cookie can linger as an empty value. Treating "present but blank" as a supplied
        // token would break a client that is correctly sending a body.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, ""))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawToken)))
                .andExpect(status().isOk());
    }

    /**
     * The security attributes, asserted identically wherever a cookie is written.
     *
     * <p>Every one of these is load-bearing and silently downgradeable: dropping {@code HttpOnly}
     * hands the durable credential back to any script on the page, dropping {@code Secure} allows
     * it over plaintext, widening {@code SameSite} reintroduces cross-site delivery, and adding a
     * {@code Domain} turns a host-only cookie into one sent to every subdomain. None of those
     * would fail a functional test — the flow keeps working perfectly while the protection is
     * gone, which is exactly why they are asserted rather than reviewed.
     */
    private void assertSecurityAttributes(String setCookie) {
        assertThat(setCookie).as("Set-Cookie must be present").isNotNull();
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax")
                .contains("Path=/api/v1/auth");
        assertThat(setCookie).as("host-only: a Domain attribute would send the refresh token to "
                + "every present and future subdomain").doesNotContain("Domain=");
    }

    @Test
    void loginIssuesTheCookieWithTheRightSecurityAttributes() throws Exception {
        // Login, not just refresh. Once the web client stops keeping the token in localStorage, a
        // freshly signed-in browser holds no refresh credential unless login sets it -- the
        // session would then die at the first access-token expiry with nothing to rotate.
        String password = "Correct-Horse-9!";
        User user = new User();
        user.setEmail("cookie-attrs-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setFullName("Cookie Attrs");
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "identifier", user.getEmail(),
                                "password", password,
                                "scope", "USER"))))
                .andExpect(status().isOk())
                .andReturn();

        assertSecurityAttributes(result.getResponse().getHeader("Set-Cookie"));
    }

    @Test
    void refreshRotatesTheCookieToTheNewToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken)))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).as("rotation must move the cookie, or the next request replays a "
                + "revoked token and reuse detection signs the user out of everything").isNotNull();
        assertThat(setCookie).doesNotContain(rawToken);
        assertSecurityAttributes(setCookie);
    }

    /** The token inside a Set-Cookie header, so a test can replay what a browser would send back. */
    private static String cookieValue(String setCookie) {
        assertThat(setCookie).isNotNull();
        String firstAttribute = setCookie.split(";", 2)[0];
        return firstAttribute.substring(firstAttribute.indexOf('=') + 1);
    }

    @Test
    void issuanceRotationInvalidationAndReuseDetectionComposeOverTheCookieTransport() throws Exception {
        // Each of these is covered on its own elsewhere -- rotation here, reuse detection in
        // RefreshTokenSessionLimitsTest against a mocked repository. What no other test covers is
        // whether they still compose once a real browser-shaped request carries them, which is the
        // seam where a cookie-specific mistake would hide: reading the wrong cookie, writing one
        // the next request cannot present, or rotating without revoking.
        // A SECOND, independent session for the same user -- a phone alongside the laptop. It is
        // never replayed and never rotated, so the only thing that can invalidate it is the
        // account-wide revocation. Written first without it, using the rotated cookie as the
        // witness, and that version passed even with revokeAllForUser deleted: the rotated cookie
        // had already been exchanged a step earlier, so it was stale from ordinary rotation and
        // proved nothing about blast radius.
        String otherDevice = refreshTokenService.issue(userId).rawToken();

        MvcResult first = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken)))
                .andExpect(status().isOk())
                .andReturn();
        String rotated = cookieValue(first.getResponse().getHeader("Set-Cookie"));

        assertThat(rotated).as("rotation must mint a new token").isNotEqualTo(rawToken);

        // Replaying the ORIGINAL cookie is the theft signal: it was already exchanged, so anyone
        // still holding it either kept a copy or stole one.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_004"));

        // The untouched second device is now signed out too. That is the whole point of the
        // response to a suspected theft -- invalidating the copy the attacker may also hold -- and
        // it is the only assertion here that a cookie layer merely rejecting stale values could
        // not satisfy.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(otherDevice)))
                .andExpect(status().isUnauthorized());

        // ...and so is the freshly rotated one the legitimate user was holding.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutClearsTheCookieEvenWithNoTokenAtAll() throws Exception {
        // Idempotent on purpose. A client whose token already expired still needs the cookie gone;
        // failing here would leave the credential in place on the one request trying to remove it.
        MvcResult result = mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andReturn();

        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).contains(RefreshTokenCookie.NAME).contains("Max-Age=0");
        // Every attribute but Max-Age must match the original, or the browser treats it as a
        // different cookie and silently keeps the credential.
        assertSecurityAttributes(setCookie);
    }

    @Test
    void logoutViaCookieRevokesTheSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(new Cookie(RefreshTokenCookie.NAME, rawToken)))
                .andExpect(status().isOk());

        // The token really is dead, not merely un-cookied.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawToken)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Bug 27. refresh() used to call rotate() -- which revokes the presented token and persists a
     * new one, joining this method's transaction rather than opening its own -- BEFORE checking
     * whether the account is suspended. Since the method is {@code noRollbackFor = ApiException},
     * throwing the suspension error afterward still committed that rotation: a real, valid,
     * persisted-but-never-handed-out token got "spent" on every rejected attempt, and the token the
     * suspended user actually presented was left revoked even though their request never succeeded.
     *
     * <p>Asserted from both directions: the request must still be rejected, AND the presented token
     * must still work once the suspension is lifted -- which only holds if rotate() never ran.
     */
    @Test
    void refreshDoesNotConsumeTheTokenWhenTheAccountIsSuspended() throws Exception {
        User user = userRepository.findById(userId).orElseThrow();
        user.setStatus(User.STATUS_SUSPENDED);
        userRepository.save(user);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawToken)))
                .andExpect(status().isForbidden());

        user.setStatus(User.STATUS_ACTIVE);
        userRepository.save(user);

        // Still the original, un-rotated token -- if rotate() had already run before the
        // suspension check, this would fail as an already-used token instead.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rawToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }
}
