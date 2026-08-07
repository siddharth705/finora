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

/** Support-assisted per-user Reconciliation Monitor + Workspace Health visibility
 *  (AdminUserWorkspaceController) -- proves RECONCILIATION_VIEW gating and that the proxied
 *  WorkspaceDashboardService.summarize() call is scoped to the userId in the path. */
class AdminUserWorkspaceControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-workspace-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Workspace IT Test User");
        user.setRole(role);
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
    void plainUser_isForbiddenFromViewingAnotherUsersWorkspace() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/workspace",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesTheTargetUsersOwnTransactionCount_notAnotherUsers() throws Exception {
        User admin = createUser("ADMIN");
        User targetUser = createUser("USER");
        User otherUser = createUser("USER");

        Account targetAccount = new Account();
        targetAccount.setUserId(targetUser.getId());
        targetAccount.setName("Target Account");
        targetAccount.setAccountType(Account.Type.SAVINGS);
        targetAccount.setBalance(BigDecimal.ZERO);
        // See BaseEntity: save() merges rather than persists, so the id lands on the returned
        // instance, not this one.
        targetAccount = accountRepository.save(targetAccount);

        Transaction t = new Transaction();
        t.setUserId(targetUser.getId());
        t.setAccountId(targetAccount.getId());
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        transactionRepository.save(t);

        // otherUser needs their own data, and specifically a *different* transaction count (2,
        // not 1) from targetUser's -- without this, the assertion below would pass identically
        // whether or not the endpoint actually scopes by userId, since an unscoped/leaky query
        // summing every user's transactions would coincidentally still show 1 if otherUser
        // contributed none. Two accounts each with one transaction, matching CategoryRulesTest/
        // TransactionRepositoryIT's own multi-account setup pattern for this entity.
        Account otherAccount = new Account();
        otherAccount.setUserId(otherUser.getId());
        otherAccount.setName("Other Account");
        otherAccount.setAccountType(Account.Type.SAVINGS);
        otherAccount.setBalance(BigDecimal.ZERO);
        otherAccount = accountRepository.save(otherAccount);

        for (int i = 0; i < 2; i++) {
            Transaction otherTxn = new Transaction();
            otherTxn.setUserId(otherUser.getId());
            otherTxn.setAccountId(otherAccount.getId());
            otherTxn.setTxnDate(LocalDate.now());
            otherTxn.setAmount(BigDecimal.valueOf(50));
            otherTxn.setTxnType(Transaction.Type.EXPENSE);
            transactionRepository.save(otherTxn);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + targetUser.getId() + "/workspace",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("totalTransactions").asLong()).isEqualTo(1L);
        assertThat(data.get("totalAccounts").asLong()).isEqualTo(1L);
        assertThat(data.get("health")).isNotNull();
    }
}
