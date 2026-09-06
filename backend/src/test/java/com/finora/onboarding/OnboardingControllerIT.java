package com.finora.onboarding;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("onboarding-controller-it-" + UUID.randomUUID() + "@example.com"); // synthetic-ok: fixture, not a real account
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Onboarding Controller IT Test User");
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
    void statusStartsIncompleteForAFreshUser() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/onboarding/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("onboardingCompleted").asBoolean()).isFalse();
        assertThat(data.get("financialFocus")).isEmpty();
    }

    @Test
    void financialFocusRoundTrips() throws Exception {
        User user = createUser();

        restTemplate.exchange("/api/v1/onboarding/financial-focus", HttpMethod.POST,
                new HttpEntity<>(Map.of("focusKeys", List.of("TRACK_SPENDING", "REDUCE_DEBT")), bearerFor(user)),
                String.class);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/onboarding/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        JsonNode focus = mapper.readTree(response.getBody()).get("data").get("financialFocus");
        List<String> values = mapper.convertValue(focus, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(values).containsExactlyInAnyOrder("TRACK_SPENDING", "REDUCE_DEBT");
    }

    @Test
    void financialFocusRejectsAnUnknownKey() {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/onboarding/financial-focus", HttpMethod.POST,
                new HttpEntity<>(Map.of("focusKeys", List.of("NOT_A_REAL_KEY")), bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void completeThenResetRoundTrips() throws Exception {
        User user = createUser();

        restTemplate.exchange("/api/v1/onboarding/complete", HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearerFor(user)), String.class);
        ResponseEntity<String> afterComplete = restTemplate.exchange(
                "/api/v1/onboarding/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(mapper.readTree(afterComplete.getBody()).get("data").get("onboardingCompleted").asBoolean()).isTrue();

        restTemplate.exchange("/api/v1/onboarding/reset", HttpMethod.POST,
                new HttpEntity<>(Map.of(), bearerFor(user)), String.class);
        ResponseEntity<String> afterReset = restTemplate.exchange(
                "/api/v1/onboarding/status", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
        assertThat(mapper.readTree(afterReset.getBody()).get("data").get("onboardingCompleted").asBoolean()).isFalse();
    }

    @Test
    void checklistStartsAtZeroOfSixForAFreshUser() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/onboarding/checklist", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("completedCount").asInt()).isZero();
        assertThat(data.get("totalCount").asInt()).isEqualTo(6);
        assertThat(data.get("items")).hasSize(6);
    }
}
