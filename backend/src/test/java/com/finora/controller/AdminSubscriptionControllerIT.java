package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.integrations.razorpay.RazorpaySubscriptionGateway;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.SubscriptionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D-28 PR4-A end to end -- proves SUBSCRIPTION_MANAGEMENT_VIEW/_MANAGE gate separately (V99),
 *  and that a manual plan change is real against the database, not just a mocked service call. */
class AdminSubscriptionControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @MockitoBean private RazorpaySubscriptionGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-subscription-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Subscription IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
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
    void plainUser_isForbiddenFromListingSubscriptions() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesASubscriptionItProvisioned_inTheList() throws Exception {
        // ADMIN holds SUBSCRIPTION_MANAGEMENT_VIEW per V99's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data").get("content");
        boolean found = false;
        for (JsonNode row : data) {
            if (row.get("userId").asText().equals(target.getId().toString())) {
                found = true;
                assertThat(row.get("planCode").asText()).isEqualTo("FREE");
                assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void plainUser_isForbiddenFromChangingAPlan() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "test"), bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_changesAUsersPlan_andItPersists() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "beta tester"), bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/subscriptions", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        JsonNode data = mapper.readTree(listResponse.getBody()).get("data").get("content");
        boolean confirmed = false;
        for (JsonNode row : data) {
            if (row.get("userId").asText().equals(target.getId().toString())) {
                assertThat(row.get("planCode").asText()).isEqualTo("PLUS");
                confirmed = true;
            }
        }
        assertThat(confirmed).isTrue();
    }

    @Test
    void admin_isBlockedFromChangingPlan_whileAUserHasAnActiveRazorpaySubscription() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(target.getId()).orElseThrow();
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "test"), bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void admin_cancelsThePaidSubscription_thenChangePlanSucceeds() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(target.getId()).orElseThrow();
        subscription.setPaymentProvider("RAZORPAY");
        subscription.setRazorpaySubscriptionId("sub_test_" + UUID.randomUUID());
        subscriptionRepository.save(subscription);

        ResponseEntity<String> cancelResponse = restTemplate.postForEntity(
                "/api/v1/admin/subscriptions/" + target.getId() + "/cancel-paid-subscription",
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(cancelResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> changePlanResponse = restTemplate.exchange(
                "/api/v1/admin/subscriptions/" + target.getId() + "/plan", HttpMethod.PUT,
                new HttpEntity<>(Map.of("planCode", "PLUS", "reason", "beta tester"), bearerFor(admin)), String.class);
        assertThat(changePlanResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void plainUser_isForbiddenFromViewingSubscriptionHealth() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/health", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** This IT suite shares one Postgres database across every test class (see other subscription
     *  ITs' own fixtures), so a fresh PAST_DUE row here is never the only one in the table --
     *  asserting a before/after DELTA, not an absolute count, is what makes this test correct
     *  regardless of what other tests have already inserted. */
    @Test
    void admin_seesSubscriptionHealthCountsIncreaseAfterANewPastDueSubscription() throws Exception {
        User admin = createUser("ADMIN");

        JsonNode before = mapper.readTree(restTemplate.exchange(
                "/api/v1/admin/subscriptions/health", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class
        ).getBody()).get("data");
        long pastDueBefore = before.get("pastDueCount").asLong();

        User target = createUser("USER");
        subscriptionService.provisionFreeSubscription(target.getId());
        var subscription = subscriptionRepository.findActiveOrTrial(target.getId()).orElseThrow();
        subscription.setStatus(com.finora.entity.Subscription.STATUS_PAST_DUE);
        subscriptionRepository.save(subscription);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/subscriptions/health", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode after = mapper.readTree(response.getBody()).get("data");

        assertThat(after.get("pastDueCount").asLong()).isEqualTo(pastDueBefore + 1);
    }
}
