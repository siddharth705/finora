package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D-28 PR4-A end to end -- a real user with a real FREE subscription (via SubscriptionService,
 *  the same path AuthService.createUserRecord/createOAuthUserRecord call on every signup) sees
 *  the right entitlement map back from the real database, not a mocked one. */
class EntitlementControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("entitlement-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Entitlement IT Test User");
        user.setAccountScope(User.SCOPE_USER);
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
    void aUserWithAFreeSubscription_seesBasicDashboardButNotFinoAi() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("planCode").asText()).isEqualTo("FREE");
        assertThat(data.get("features").get("BASIC_DASHBOARD").asBoolean()).isTrue();
        // V99's own seed never grants FINO_AI to Free -- absent from the map entirely, same as
        // EntitlementService.hasEntitlement's own "no row = false" contract.
        assertThat(data.get("features").has("FINO_AI")).isFalse();
    }

    @Test
    void aUserWithNoSubscriptionAtAll_getsAnEmptyEntitlementMap_notAnError() throws Exception {
        User user = createUser(); // deliberately skips provisionFreeSubscription

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/entitlements", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("planCode").isNull()).isTrue();
        assertThat(data.get("features").size()).isEqualTo(0);
    }
}
