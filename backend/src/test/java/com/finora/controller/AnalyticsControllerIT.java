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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The self-service Analytics views (top-merchants/trend/category-confidence/top-categories/
 * learning-growth) restored to a real user, this time gated behind
 * FeatureEntitlement.ADVANCED_REPORTS -- the first FeatureEntitlement key any endpoint actually
 * enforces (EntitlementService.hasEntitlement had zero call sites before this). Proves the gate
 * fails CLOSED for Free/no-subscription users and opens for Plus/Premium, against real
 * Plan/Subscription/FeatureEntitlement rows seeded by V99__billing_entitlements.sql, not mocks.
 *
 * importStatistics is covered separately below to confirm the one pre-existing self-service view
 * is untouched by this change -- it was never part of the ADVANCED_REPORTS gate.
 */
class AnalyticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final List<String> ADVANCED_REPORTS_PATHS = List.of(
            "/api/v1/analytics/top-merchants", "/api/v1/analytics/trend",
            "/api/v1/analytics/category-confidence", "/api/v1/analytics/top-categories",
            "/api/v1/analytics/learning-growth");

    private User createUser() {
        User user = new User();
        user.setEmail("analytics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Analytics IT Test User");
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

    private ResponseEntity<String> get(String path, User user) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void aFreeUser_isDeniedEveryAdvancedReportsViewWithTheEntitlementErrorCode() throws Exception {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        for (String path : ADVANCED_REPORTS_PATHS) {
            ResponseEntity<String> response = get(path, user);

            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.FORBIDDEN);
            JsonNode body = mapper.readTree(response.getBody());
            assertThat(body.get("errorCode").asText()).as(path).isEqualTo("ENTITLEMENT_001");
        }
    }

    @Test
    void aUserWithNoSubscriptionAtAll_isAlsoDenied_failingClosedNotOpen() {
        User user = createUser(); // deliberately skips provisionFreeSubscription

        ResponseEntity<String> response = get("/api/v1/analytics/top-merchants", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aPlusUser_seesEveryAdvancedReportsView() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());

        for (String path : ADVANCED_REPORTS_PATHS) {
            ResponseEntity<String> response = get(path, user);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void aPremiumUser_alsoSeesAdvancedReportsView_confirmingBothPaidTiersAreSeeded() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());
        subscriptionService.changePlan(user.getId(), "PREMIUM", "test-upgrade", user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/top-merchants", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void importStatisticsView_staysSelfServiceForEveryPlan_untouchedByTheAdvancedReportsGate() {
        User user = createUser();
        subscriptionService.provisionFreeSubscription(user.getId());

        ResponseEntity<String> response = get("/api/v1/analytics/merchants?view=importStatistics", user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
