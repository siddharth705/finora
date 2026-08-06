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
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Platform-wide spend analytics (AdminPlatformAnalyticsService) -- proves
 *  PLATFORM_ANALYTICS_VIEW gating (V30's new permission) and that two different users' own
 *  private categories sharing the same name get combined into one platform-wide total (the
 *  exact scenario the name-based re-grouping exists for -- see the service's class comment). */
class AdminPlatformAnalyticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-platform-analytics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Platform Analytics IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), java.util.UUID.randomUUID()));
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

    private void expenseFor(User user, Account account, UUID categoryId, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setUserId(user.getId());
        t.setAccountId(account.getId());
        t.setCategoryId(categoryId);
        t.setTxnDate(LocalDate.now());
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        transactionRepository.save(t);
    }

    @Test
    void plainUser_isForbiddenFromViewingPlatformAnalytics() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/analytics/platform", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void topCategories_combinesTwoDifferentUsersCategoriesThatShareAName() throws Exception {
        User admin = createUser("ADMIN");
        User userOne = createUser("USER");
        User userTwo = createUser("USER");
        String uniqueName = "Test Category " + UUID.randomUUID();

        Category categoryOne = new Category();
        categoryOne.setUserId(userOne.getId());
        categoryOne.setName(uniqueName);
        categoryRepository.save(categoryOne);

        Category categoryTwo = new Category();
        categoryTwo.setUserId(userTwo.getId());
        categoryTwo.setName(uniqueName);
        categoryRepository.save(categoryTwo);

        // Bug fix: unlike AdminMerchantStatsControllerIT's platformMerchantCounts() (an unbounded
        // list), topCategories() is capped to the top 10 by spend (TOP_LIMIT, mirroring
        // AnalyticsService's own convention) -- against the same shared Postgres container every
        // other IT class in this suite also writes to, a small test amount (30 + 70) risked
        // getting pushed out of the top 10 by other tests' data, making this test flaky rather
        // than reliably red/green. Large, easily-dominant amounts make that practically
        // impossible while keeping the combined total (100,000,000.00) simple to assert on.
        expenseFor(userOne, accountFor(userOne), categoryOne.getId(), new BigDecimal("30000000.00"));
        expenseFor(userTwo, accountFor(userTwo), categoryTwo.getId(), new BigDecimal("70000000.00"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/analytics/platform", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode row = null;
        for (JsonNode candidate : data.get("topCategories")) {
            if (candidate.get("categoryName").asText().equals(uniqueName)) {
                row = candidate;
                break;
            }
        }
        assertThat(row).as("platform category row for " + uniqueName).isNotNull();
        assertThat(row.get("totalSpend").asDouble()).isEqualTo(100000000.00);
        assertThat(row.get("transactionCount").asLong()).isEqualTo(2L);
    }
}
