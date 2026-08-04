package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
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

/**
 * Admin, support-assisted per-user analytics (AdminUserAnalyticsController) -- proves
 * PLATFORM_ANALYTICS_VIEW gating and that each view is scoped to the userId in the path, not
 * leaking another user's spend into the response. importStatistics is deliberately not covered
 * here -- it stays on the self-service AnalyticsController (see that class's own doc comment) and
 * has no admin-side equivalent.
 */
class AdminUserAnalyticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-analytics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Analytics IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** One EXPENSE transaction attributed to a named merchant, for the given user. */
    private void expenseTransactionFor(User user, String merchantName, BigDecimal amount) {
        Merchant merchant = new Merchant();
        merchant.setUserId(user.getId());
        merchant.setCanonicalName(merchantName);
        merchantRepository.save(merchant);

        Account account = new Account();
        account.setUserId(user.getId());
        account.setName(user.getId() + " Account");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        // Must use the RETURN value: Account extends BaseEntity, whose @Version field is a
        // non-null Long, so Spring Data treats it as not-new and calls merge() rather than
        // persist(). merge() returns a managed copy and leaves this instance's id null, so the
        // transaction below was inserted with account_id = NULL and rejected by the NOT NULL
        // constraint. Merchant above has no @Version, which is why it works either way.
        account = accountRepository.save(account);

        Transaction txn = new Transaction();
        txn.setUserId(user.getId());
        txn.setAccountId(account.getId());
        txn.setTxnDate(LocalDate.now());
        txn.setDescription(merchantName + " purchase");
        txn.setAmount(amount);
        txn.setTxnType(Transaction.Type.EXPENSE);
        txn.setMerchantId(merchant.getId());
        transactionRepository.save(txn);
    }

    @Test
    void plainUser_isForbiddenFromViewingAnotherUsersAnalytics() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/top-merchants",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesOnlyTheTargetUsersTopMerchants_notAnotherUsers() throws Exception {
        // ADMIN holds PLATFORM_ANALYTICS_VIEW per V30__platform_analytics_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        User other = createUser("USER");
        expenseTransactionFor(target, "Swiggy", BigDecimal.valueOf(500));
        expenseTransactionFor(other, "A Different User's Merchant", BigDecimal.valueOf(9999));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/top-merchants",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("merchantName").asText()).isEqualTo("Swiggy");
    }

    @Test
    void admin_getsTheTargetUsersTrendAndCategoryConfidenceAndTopCategoriesAndLearningGrowth() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        expenseTransactionFor(target, "Amazon", BigDecimal.valueOf(1200));
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> trend = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/trend",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(trend.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> confidence = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/category-confidence",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(confidence.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> topCategories = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/top-categories",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(topCategories.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> learningGrowth = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/analytics/learning-growth",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(learningGrowth.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
