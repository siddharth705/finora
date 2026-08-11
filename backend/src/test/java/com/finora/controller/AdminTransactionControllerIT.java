package com.finora.controller;

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

/**
 * Support-assisted transaction visibility + deletion for a specific user (AdminTransactionController,
 * gated class-wide by TRANSACTION_DELETE -- see the controller's own doc comment for why even the
 * read-only list endpoint sits behind the delete permission rather than a separate view permission).
 * Proves that gating and that DELETE performs the real soft-delete TransactionService.delete
 * implements (balance adjustment, reconciliation/recurring re-run, audit write), not just a 200.
 */
class AdminTransactionControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-transactions-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Transactions IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
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

    private Account createAccount(User owner, BigDecimal balance) {
        Account a = new Account();
        a.setUserId(owner.getId());
        a.setName("Support Account");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(balance);
        return accountRepository.save(a);
    }

    private Transaction createTransaction(User owner, Account account, BigDecimal amount) {
        Transaction t = new Transaction();
        t.setUserId(owner.getId());
        t.setAccountId(account.getId());
        t.setTxnDate(LocalDate.of(2026, 7, 1));
        t.setAmount(amount);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setDescription("Test expense");
        t.setSource(Transaction.Source.MANUAL);
        return transactionRepository.save(t);
    }

    @Test
    void plainUser_isForbiddenFromListingAnotherUsersTransactions() {
        User user = createUser("USER");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/transactions",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromDeletingAnotherUsersTransaction() {
        User user = createUser("USER");
        User target = createUser("USER");
        Account account = createAccount(target, BigDecimal.valueOf(1000));
        Transaction txn = createTransaction(target, account, BigDecimal.valueOf(100));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/transactions/" + txn.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(transactionRepository.findById(txn.getId())).isPresent();
    }

    @Test
    void admin_canListAnotherUsersTransactions() {
        // ADMIN holds TRANSACTION_DELETE per V16__rbac_roles_permissions.sql's seed grant, which
        // gates this class-wide, including the read-only list endpoint.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target, BigDecimal.valueOf(1000));
        Transaction txn = createTransaction(target, account, BigDecimal.valueOf(100));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/transactions",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(txn.getId().toString());
    }

    @Test
    void admin_deletingATransaction_softDeletesItAndAdjustsTheAccountBalance() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target, BigDecimal.valueOf(1000));
        Transaction txn = createTransaction(target, account, BigDecimal.valueOf(100));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/transactions/" + txn.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // TransactionService.delete soft-deletes via @SQLDelete/@SQLRestriction -- findById no
        // longer sees the row through the normal (deleted_at IS NULL) query path.
        assertThat(transactionRepository.findById(txn.getId())).isEmpty();
        // adjustAccountBalance negates the deleted expense back onto the account.
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo(BigDecimal.valueOf(1100));
    }

    @Test
    void deletingATransactionTwice_returnsNotFoundOnTheSecondAttempt() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target, BigDecimal.valueOf(1000));
        Transaction txn = createTransaction(target, account, BigDecimal.valueOf(100));
        HttpHeaders headers = bearerFor(admin);
        String url = "/api/v1/admin/users/" + target.getId() + "/transactions/" + txn.getId();

        ResponseEntity<String> first = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        // OwnershipGuard.requireOwned() 404s once the row is invisible via the soft-delete
        // @SQLRestriction, same as deleting an id that never existed.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingANonexistentTransaction_returnsNotFound() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/transactions/" + UUID.randomUUID(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
