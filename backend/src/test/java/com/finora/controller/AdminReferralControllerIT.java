package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.AuthDtos.RegisterRequest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AuthService;
import com.finora.service.ReferralService;
import com.finora.service.SubscriptionService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D-28 PR4-C end to end -- proves REFERRAL_MANAGEMENT_VIEW/_MANAGE gate separately (V101), and
 *  the full lifecycle against a real database: a referral code redeemed at registration
 *  (REGISTERED), a real plan change advancing it (SUBSCRIBED), and an admin credit finishing it
 *  (REWARDED, with the wallet balance actually moving) -- the same "prove it end to end, not
 *  per-layer" discipline every other cross-cutting feature in this codebase gets. */
class AdminReferralControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ReferralService referralService;
    @Autowired private SubscriptionService subscriptionService;
    @Autowired private AuthService authService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-referral-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Referral IT Test User");
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
    void plainUser_isForbiddenFromListingReferrals() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromCreditingAReward() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/referrals/" + UUID.randomUUID() + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 100, "reason", "test"), bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void fullLifecycle_registrationToSubscriptionToCreditedReward_movesTheWalletBalance() throws Exception {
        // 1. Referrer generates a real code.
        User referrer = createUser("USER");
        String code = referralService.myCode(referrer.getId());

        // 2. A brand-new signup redeems it -- the real AuthService.register() path, not a direct
        //    ReferralService call, so this proves the actual registration wiring, not just the
        //    service method in isolation.
        String referredEmail = "referred-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(referredEmail, "Password123", "Referred Person",
                "+919876500777" /* synthetic-ok */, code));
        User referred = userRepository.findByEmailIgnoreCaseAndAccountScope(referredEmail, User.SCOPE_USER)
                .orElseThrow();

        User admin = createUser("ADMIN");

        ResponseEntity<String> afterRegister = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        JsonNode row = findRow(afterRegister, referred.getId());
        assertThat(row.get("status").asText()).isEqualTo("REGISTERED");
        UUID referralId = UUID.fromString(row.get("referralId").asText());

        // 3. A real plan change (the only way to reach a paid plan today, PR4-A) advances it to
        //    SUBSCRIBED automatically. No separate provisionFreeSubscription call here --
        //    authService.register() above already provisioned Free as part of the real
        //    registration path (AuthService.createUserRecord); a second call would violate
        //    subscriptions' own one-active-subscription-per-user constraint (V99).
        subscriptionService.changePlan(referred.getId(), "PLUS", "beta tester", admin.getId());

        ResponseEntity<String> afterSubscribe = restTemplate.exchange(
                "/api/v1/admin/referrals", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(findRow(afterSubscribe, referred.getId()).get("status").asText()).isEqualTo("SUBSCRIBED");

        // 4. Admin credits the reward -- the wallet balance actually moves, checked via the
        //    referrer's own GET /referrals/mine, not just the admin dashboard's own echo of it.
        ResponseEntity<String> creditResponse = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "successful referral"), bearerFor(admin)), String.class);
        assertThat(creditResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> mineResponse = restTemplate.exchange(
                "/api/v1/referrals/mine", HttpMethod.GET, new HttpEntity<>(bearerFor(referrer)), String.class);
        JsonNode mine = mapper.readTree(mineResponse.getBody()).get("data");
        assertThat(mine.get("walletBalance").asDouble()).isEqualTo(250.0);
        assertThat(mine.get("referrals").get(0).get("status").asText()).isEqualTo("REWARDED");

        // A second credit attempt against the same, now-REWARDED referral must be rejected --
        // the idempotency guarantee proposal §8 asks for.
        ResponseEntity<String> secondCredit = restTemplate.exchange(
                "/api/v1/admin/referrals/" + referralId + "/credit", HttpMethod.POST,
                new HttpEntity<>(Map.of("amount", 250, "reason", "duplicate attempt"), bearerFor(admin)), String.class);
        assertThat(secondCredit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private JsonNode findRow(ResponseEntity<String> response, UUID referredUserId) throws Exception {
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        for (JsonNode row : data) {
            if (row.get("referredUserId").asText().equals(referredUserId.toString())) return row;
        }
        throw new AssertionError("No referral row found for referredUserId=" + referredUserId);
    }
}
