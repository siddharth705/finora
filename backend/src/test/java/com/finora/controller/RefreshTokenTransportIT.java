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

    private String rawToken;

    @BeforeEach
    void signIn() {
        User user = new User();
        user.setEmail("transport-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Transport Test");
        user = userRepository.save(user);
        rawToken = refreshTokenService.issue(user.getId()).rawToken();
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
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        // Host-only and path-scoped: no Domain attribute, so it is never sent to another subdomain.
        assertThat(setCookie).doesNotContain("Domain=");
        assertThat(setCookie).contains("Path=/api/v1/auth");
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
        assertThat(setCookie).contains("Path=/api/v1/auth").doesNotContain("Domain=");
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
}
