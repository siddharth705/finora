package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Reconciliation Explorer (ReconciliationExplorerService) -- proves RECONCILIATION_VIEW gating
 *  and that a real transaction's raw/normalized/classification blocks come back correctly. */
class AdminReconciliationExplorerControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-reconciliation-explorer-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Reconciliation Explorer IT Test User");
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

    private Account accountFor(User user) {
        Account a = new Account();
        a.setUserId(user.getId());
        a.setName("Test Account");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.ZERO);
        return accountRepository.save(a);
    }

    @Test
    void plainUser_isForbiddenFromUsingTheExplorer() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/reconciliation/explorer/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_getsNotFound_forATransactionIdThatDoesNotExist() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/reconciliation/explorer/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void admin_seesTheRawNormalizedAndClassificationBlocks_forARealTransaction() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");
        Account account = accountFor(contributor);
        Transaction t = new Transaction();
        t.setUserId(contributor.getId());
        t.setAccountId(account.getId());
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(new BigDecimal("340.00"));
        t.setTxnType(Transaction.Type.INCOME);
        t.setDescription("REFUND ZOMATO 340.00");
        t.setMerchant("Zomato");
        t.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        t = transactionRepository.save(t);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/reconciliation/explorer/" + t.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("raw").get("transactionId").asText()).isEqualTo(t.getId().toString());
        assertThat(data.get("raw").get("description").asText()).isEqualTo("REFUND ZOMATO 340.00");
        assertThat(data.get("normalized").get("merchant").asText()).isEqualTo("Zomato");
        assertThat(data.get("edges").isArray()).isTrue();
        assertThat(data.get("edges")).isEmpty();
        assertThat(data.get("classification").get("reconciliationStatus").asText()).isEqualTo("REFUND");
    }
}
