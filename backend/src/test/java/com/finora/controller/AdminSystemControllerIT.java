package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.StatementImportRepository;
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

/**
 * Proves the admin System Health panel (frontend-admin/) is gated by SYSTEM_SETTINGS and returns
 * a real Actuator-backed verdict (status UP, with a live Postgres testcontainer backing it) --
 * not just that the endpoint exists.
 */
class AdminSystemControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private StatementImportRepository statementImportRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-system-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin System IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromSystemHealth() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/system/health", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesUpStatusBackedByTheRealTestDatabase() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/system/health", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"", "\"components\"");
    }

    private Account accountFor(User user) {
        Account a = new Account();
        a.setUserId(user.getId());
        a.setName("Test Account");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    private StatementImport importFor(User user, Account account, String fileName, int skipped) {
        StatementImport si = new StatementImport();
        si.setUserId(user.getId());
        si.setAccountId(account.getId());
        si.setFileName(fileName);
        si.setFileContent(new byte[0]);
        si.setTransactionsImported(5);
        si.setTransactionsSkipped(skipped);
        return statementImportRepository.save(si);
    }

    @Test
    void plainUser_isForbiddenFromRecentImports() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/system/recent-imports", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void recentImports_surfacesARealImport_withTheHadSkippedRowsSignal() throws Exception {
        // Admin Portal Phase 7 -- proves the honest "hadSkippedRows" per-row signal (not a
        // fabricated "status") reflects a real StatementImport row, and that the owning user's
        // email is resolved rather than left as a bare id.
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Account account = accountFor(owner);
        String uniqueFileName = "phase7-test-" + UUID.randomUUID() + ".csv";
        StatementImport saved = importFor(owner, account, uniqueFileName, 3);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/system/recent-imports", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("id").asText().equals(saved.getId().toString())) { row = candidate; break; }
        }
        assertThat(row).as("recent-imports row for " + uniqueFileName).isNotNull();
        assertThat(row.get("fileName").asText()).isEqualTo(uniqueFileName);
        assertThat(row.get("userEmail").asText()).isEqualTo(owner.getEmail());
        assertThat(row.get("transactionsSkipped").asInt()).isEqualTo(3);
        assertThat(row.get("hadSkippedRows").asBoolean()).isTrue();
    }

    @Test
    void recentImports_reportsHadSkippedRowsFalse_whenNothingWasSkipped() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        Account account = accountFor(owner);
        String uniqueFileName = "phase7-clean-" + UUID.randomUUID() + ".csv";
        StatementImport saved = importFor(owner, account, uniqueFileName, 0);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/system/recent-imports", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data) {
            if (candidate.get("id").asText().equals(saved.getId().toString())) { row = candidate; break; }
        }
        assertThat(row).as("recent-imports row for " + uniqueFileName).isNotNull();
        assertThat(row.get("hadSkippedRows").asBoolean()).isFalse();
    }
}
