package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
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

/** Insight Explorer (InsightsExplorerService) -- proves INSIGHTS_EXPLORER_VIEW gating and that a
 *  real user's total spend / top category / top merchant trace back to the right transactions. */
class AdminInsightsExplorerControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-insights-explorer-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Insights Explorer IT Test User");
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
                "/api/v1/admin/insights/explorer/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_getsNotFound_forAUserIdThatDoesNotExist() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/insights/explorer/" + UUID.randomUUID(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void admin_seesTotalSpendTopCategoryAndTopMerchant_forARealUsersExpenses() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");
        Account account = accountFor(contributor);
        Category dining = new Category();
        dining.setUserId(contributor.getId());
        dining.setName("Dining");
        dining = categoryRepository.save(dining);

        Transaction t = new Transaction();
        t.setUserId(contributor.getId());
        t.setAccountId(account.getId());
        t.setCategoryId(dining.getId());
        t.setTxnDate(LocalDate.of(2026, 7, 10));
        t.setAmount(new BigDecimal("340.00"));
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("REFUND ZOMATO 340.00");
        t.setMerchant("Zomato");
        t = transactionRepository.save(t);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/insights/explorer/" + contributor.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("reportingMonth").asText()).isEqualTo("2026-07");
        assertThat(data.get("totalSpend").get("amount").asDouble()).isEqualTo(340.0);
        assertThat(data.get("totalSpend").get("transactions").get(0).get("transactionId").asText())
                .isEqualTo(t.getId().toString());
        assertThat(data.get("topCategory").get("category").asText()).isEqualTo("Dining");
        assertThat(data.get("topMerchant").get("merchant").asText()).isEqualTo("Zomato");
    }

    @Test
    void admin_getsAnAllNullTrace_forAUserWithNoTransactions() throws Exception {
        User admin = createUser("ADMIN");
        User contributor = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/insights/explorer/" + contributor.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("reportingMonth").isNull()).isTrue();
        assertThat(data.get("totalSpend").isNull()).isTrue();
    }
}
