package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.FeatureFlag;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.FeatureFlagRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin Portal Phase 8 -- proves the feature_flags table (V32) is a real toggle, not UI theater:
 * gated by SYSTEM_SETTINGS same as PlatformSettingsControllerIT, seeded RECURRING_DETECTION_ENABLED
 * row exists from the migration itself (not created by this test), and flipping it via the API
 * actually changes what RecurringService.detectForUser does on the very next call.
 *
 * Restores RECURRING_DETECTION_ENABLED to true in @AfterEach for the same reason
 * PlatformSettingsControllerIT restores its singleton row -- this suite shares one Postgres
 * container with no per-test rollback, so leaving the flag disabled here would silently break
 * recurring-detection behavior for any later test in another IT class.
 */
class AdminFeatureFlagControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void restoreFlag() {
        featureFlagRepository.findByKey("RECURRING_DETECTION_ENABLED").ifPresent(f -> {
            f.setEnabled(true);
            featureFlagRepository.save(f);
        });
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("feature-flag-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Feature Flag IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Seeds three same-merchant, same-amount, monthly-spaced expense transactions -- a pattern
     *  RecurringServiceTest already proves the detection algorithm flags as recurring -- so tests
     *  below can tell "the flag actually gated detection" apart from "there was nothing to
     *  detect anyway". */
    private void seedRecurringNetflixTransactions(UUID userId) {
        Account account = new Account();
        account.setUserId(userId);
        account.setName("Test Checking");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.valueOf(10000));
        UUID accountId = accountRepository.save(account).getId();

        for (LocalDate date : List.of(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 6, 5), LocalDate.of(2026, 7, 5))) {
            Transaction t = new Transaction();
            t.setUserId(userId);
            t.setAccountId(accountId);
            t.setTxnDate(date);
            t.setMerchant("netflix");
            t.setAmount(BigDecimal.valueOf(649));
            t.setTxnType(Transaction.Type.EXPENSE);
            t.setSource(Transaction.Source.MANUAL);
            transactionRepository.save(t);
        }
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), java.util.UUID.randomUUID()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromFeatureFlags() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/feature-flags", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesTheSeededRecurringDetectionFlag() throws Exception {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/feature-flags", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode flag = null;
        for (JsonNode candidate : data) {
            if (candidate.get("key").asText().equals("RECURRING_DETECTION_ENABLED")) { flag = candidate; break; }
        }
        assertThat(flag).as("RECURRING_DETECTION_ENABLED seeded by V32").isNotNull();
        assertThat(flag.get("enabled").asBoolean()).isTrue();
    }

    @Test
    void admin_canDisableAFlag_andRecurringDetectionActuallyStopsRunning() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        FeatureFlag flag = featureFlagRepository.findByKey("RECURRING_DETECTION_ENABLED").orElseThrow();

        // Seed a real recurring pattern first -- without this, /recurring returns an empty list
        // no matter what the flag is set to, and the assertions below would pass even if the
        // gate in RecurringService were never actually wired up.
        User owner = createUser("USER");
        seedRecurringNetflixTransactions(owner.getId());

        // Sanity check: with the flag still on (V32's default), detection finds the pattern.
        ResponseEntity<String> beforeResponse = restTemplate.exchange(
                "/api/v1/recurring", HttpMethod.GET, new HttpEntity<>(bearerFor(owner)), String.class);
        assertThat(beforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode beforeData = mapper.readTree(beforeResponse.getBody()).get("data");
        assertThat(beforeData).as("recurring pattern should be detected while the flag is enabled").isNotEmpty();

        ResponseEntity<String> disableResponse = restTemplate.exchange(
                "/api/v1/admin/feature-flags/" + flag.getId(), HttpMethod.PUT,
                new HttpEntity<>("{\"enabled\":false}", headers), String.class);
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = mapper.readTree(disableResponse.getBody()).get("data");
        assertThat(updated.get("enabled").asBoolean()).isFalse();

        // Real behavioral proof, not just a flipped column: with the flag off, /recurring returns
        // an empty list even though the exact same recurring-pattern data is still there.
        ResponseEntity<String> recurringResponse = restTemplate.exchange(
                "/api/v1/recurring", HttpMethod.GET, new HttpEntity<>(bearerFor(owner)), String.class);
        assertThat(recurringResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode recurringData = mapper.readTree(recurringResponse.getBody()).get("data");
        assertThat(recurringData.isArray()).isTrue();
        assertThat(recurringData).isEmpty();
    }
}
