package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>TEMPORARY -- removed together with the endpoint it covers.</b>
 *
 * <p>The Sentry pipeline verification endpoint exists to be called once against production, so the
 * only thing worth asserting here is the part that would be dangerous to get wrong: that it is
 * gated on SYSTEM_SETTINGS rather than on this controller's class-level PLATFORM_DIAGNOSTICS_VIEW,
 * and that it never throws.
 *
 * <p>What it cannot assert is whether an event reaches Sentry. The SDK is deliberately disabled
 * under test (no SENTRY_DSN), so {@code captureMessage} returns the empty id and transmits nothing
 * -- which is exactly why the real verification has to happen against production and cannot be
 * automated here.
 */
class AdminSentryVerificationIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("sentry-verify-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Sentry Verification IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void plainUser_cannotEmitAVerificationEvent() {
        User user = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/diagnostics/sentry-test",
                HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /**
     * ADMIN holds SYSTEM_SETTINGS (V16), so this proves the method-level annotation actually took
     * effect. If the class-level PLATFORM_DIAGNOSTICS_VIEW were still governing, the endpoint would
     * be reachable by a read-only diagnostics role -- which is the mistake the override exists to
     * prevent, and which no other assertion here would catch.
     */
    @Test
    void anAdminCanEmitAVerificationEvent_andTheResponseSaysWhetherSentryIsOn() throws Exception {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/diagnostics/sentry-test",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).path("data");
        assertThat(data.path("marker").asText()).isNotBlank();
        assertThat(data.path("eventId").asText()).isNotBlank();
        assertThat(data.path("sentryEnabled").asText())
                .as("no DSN under test, so the SDK is off -- this field is what tells an operator "
                        + "in production whether the event was transmitted or silently dropped")
                .isEqualTo("false");
    }

    /** Every call carries its own marker, so a production run can be matched to one exact event. */
    @Test
    void eachCallCarriesADistinctMarker() throws Exception {
        User admin = createUser("ADMIN");
        HttpEntity<Void> request = new HttpEntity<>(bearerFor(admin));

        String first = mapper.readTree(restTemplate.exchange(
                "/api/v1/admin/diagnostics/sentry-test", HttpMethod.POST, request, String.class)
                .getBody()).path("data").path("marker").asText();
        String second = mapper.readTree(restTemplate.exchange(
                "/api/v1/admin/diagnostics/sentry-test", HttpMethod.POST, request, String.class)
                .getBody()).path("data").path("marker").asText();

        assertThat(first).isNotEqualTo(second);
    }
}
