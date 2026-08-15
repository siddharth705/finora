package com.finora.integrations.google;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The endpoint-level boundary for the Gmail connection flow — the half a unit test cannot prove,
 * because it is decided by {@code SecurityConfig} and the servlet stack rather than by service code.
 *
 * <p>The asymmetry being pinned: {@code /callback} MUST be reachable unauthenticated (Google
 * redirects a browser to it, carrying no credentials of Finora's), while every other endpoint in
 * this tree must NOT be. Getting that backwards in either direction is a real defect — an
 * authenticated callback is unreachable by construction, and an unauthenticated status/disconnect
 * endpoint leaks or destroys another user's connection.
 */
class GmailOAuthEndpointIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/integrations/google/gmail";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    @org.springframework.boot.test.web.server.LocalServerPort private int port;

    /**
     * Hits the callback with a client that will not follow the redirect.
     *
     * <p>The 302 and its {@code Location} ARE the behaviour under test, so a following client
     * reports the status of wherever it landed and every assertion here would prove nothing.
     *
     * <p>Explicitly disabled on the connection rather than via
     * {@code TestRestTemplate.HttpClientOption}: that option only applies when Apache HttpClient 5
     * is on the classpath, and it is not here, so {@code TestRestTemplate} falls back to the JDK's
     * {@code HttpURLConnection} — which follows redirects by default and ignores the option
     * entirely. Before this was pinned, these tests were issuing a live request to whatever
     * {@code post-connect-redirect} pointed at, which in the default configuration is the
     * production frontend. The test profile now also points that at localhost, as a second layer.
     */
    private ResponseEntity<Void> callbackWithoutFollowingRedirects(String query) {
        SimpleClientHttpRequestFactory noFollow = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        RestTemplate plain = new RestTemplate(noFollow);
        // A 302 is the expected outcome here, not an error -- the default handler would throw on it.
        plain.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override public boolean hasError(org.springframework.http.client.ClientHttpResponse r) { return false; }
        });
        return plain.exchange("http://localhost:" + port + BASE + "/callback" + query,
                HttpMethod.GET, HttpEntity.EMPTY, Void.class);
    }

    private User createUser() {
        User user = new User();
        user.setEmail("gmail-oauth-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Gmail OAuth IT User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    /**
     * The callback is the one endpoint that must answer an anonymous request, because Google's
     * redirect is a plain browser navigation. If this ever starts returning 401/403, the whole flow
     * is dead on arrival — the user completes consent and lands on an error.
     */
    @Test
    @DisplayName("the callback is reachable without authentication")
    void callback_isReachableAnonymously() {
        ResponseEntity<Void> response = callbackWithoutFollowingRedirects("?error=access_denied");

        assertThat(response.getStatusCode())
                .as("Google's redirect carries no Finora credentials -- requiring auth here makes "
                        + "the callback unreachable rather than more secure")
                .isNotIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /** A declined consent is a normal outcome, not an error: send the user back, do not 500. */
    @Test
    void callback_whenConsentIsDeclined_redirectsBackToTheApp() {
        ResponseEntity<Void> response = callbackWithoutFollowingRedirects("?error=access_denied");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        assertThat(response.getHeaders().getLocation().toString()).contains("gmail=declined");
    }

    /**
     * A forged state must not be distinguishable from an expired one, and must not produce a stack
     * trace or a 500 — this endpoint is anonymous and therefore reachable by anyone.
     */
    @Test
    void callback_withAForgedState_redirectsWithAFailureMarkerRatherThanErroring() {
        ResponseEntity<Void> response =
                callbackWithoutFollowingRedirects("?code=whatever&state=forged-not-in-the-database");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation().toString()).contains("gmail=failed");
    }

    @Test
    void callback_withNoCodeAndNoState_redirectsAsInvalid() {
        ResponseEntity<Void> response = callbackWithoutFollowingRedirects("");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation().toString()).contains("gmail=invalid");
    }

    // ---- everything else stays authenticated ----

    @Test
    void status_requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/status", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void connect_requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/connect", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    void verify_requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/connection/verify", HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /**
     * With Google unconfigured — the test profile's deliberate state, and any deployment that has
     * not set the env vars — verify answers 503, the same as connect. It does NOT report a
     * connection problem: the fault is the deployment's, and telling a user to reconnect would send
     * them to fix something they cannot.
     */
    @Test
    void verify_whenGoogleIsNotConfigured_is503() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/connection/verify", HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void disconnect_requiresAuthentication() {
        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/connection", HttpMethod.DELETE, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    /**
     * With no Google client configured — which is the state of the test profile, and of any
     * deployment that has not set the env vars — status still answers, reporting the feature as
     * unavailable. The UI needs that to hide the entry point rather than offer a button that 503s.
     */
    @Test
    void status_whenGoogleIsNotConfigured_reportsUnavailableRatherThanFailing() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"connected\":false");
        assertThat(response.getBody()).contains("\"available\":false");
    }

    /** Connecting on a deployment with no Google client is 503, not a confusing 500 or a redirect
     *  to a half-built Google URL. */
    @Test
    void connect_whenGoogleIsNotConfigured_is503() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/connect", HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    /** No credential material may appear in what reaches a browser -- the status payload is the
     *  one response shape that is derived from a row holding a token. */
    @Test
    void status_neverExposesCredentialMaterial() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                BASE + "/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getBody())
                .doesNotContain("refreshToken")
                .doesNotContain("encryptedRefreshToken")
                .doesNotContain("encryptionKeyId");
    }
}
