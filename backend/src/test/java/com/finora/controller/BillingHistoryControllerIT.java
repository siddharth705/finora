package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Payment;
import com.finora.entity.User;
import com.finora.repository.PaymentRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** D-28 PR4-B end to end -- a real user against a real (empty) payments table sees an empty
 *  billing history, not an error; a user with a real payment row sees only their own. */
class BillingHistoryControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private PaymentRepository paymentRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("billing-history-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Billing History IT Test User");
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
    void aUserWithNoPayments_getsAnEmptyHistory_notAnError() throws Exception {
        User user = createUser();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/history", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isZero();
    }

    @Test
    void aUserOnlySeesTheirOwnPayments() throws Exception {
        User owner = createUser();
        User other = createUser();

        Payment mine = new Payment();
        mine.setUserId(owner.getId());
        mine.setAmount(BigDecimal.valueOf(499));
        mine.setCurrency("INR");
        mine.setProvider("RAZORPAY");
        mine.setStatus(Payment.STATUS_SUCCESS);
        paymentRepository.save(mine);

        Payment notMine = new Payment();
        notMine.setUserId(other.getId());
        notMine.setAmount(BigDecimal.valueOf(999));
        notMine.setCurrency("INR");
        notMine.setStatus(Payment.STATUS_SUCCESS);
        paymentRepository.save(notMine);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/billing/history", HttpMethod.GET, new HttpEntity<>(bearerFor(owner)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).get("amount").asDouble()).isEqualTo(499.0);
        assertThat(data.get(0).get("status").asText()).isEqualTo(Payment.STATUS_SUCCESS);
    }
}
