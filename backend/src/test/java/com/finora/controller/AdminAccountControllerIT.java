package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
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

/**
 * Support-assisted account management (AdminAccountController) -- an admin creating, editing and
 * deleting a specific user's financial accounts on their behalf. Proves ACCOUNT_CREATE/
 * ACCOUNT_UPDATE/ACCOUNT_DELETE gating for each endpoint independently (the controller has no
 * class-level @PreAuthorize, so each method's own annotation has to actually be doing the work),
 * and that DELETE performs the real (soft-)delete AccountService.delete implements rather than
 * just returning 200.
 */
class AdminAccountControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-accounts-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Accounts IT Test User");
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

    private Account createAccount(User owner) {
        Account a = new Account();
        a.setUserId(owner.getId());
        a.setName("Existing Savings");
        a.setAccountType(Account.Type.SAVINGS);
        a.setBalance(BigDecimal.valueOf(1000));
        return accountRepository.save(a);
    }

    @Test
    void plainUser_isForbiddenFromListingAnotherUsersAccounts() {
        User user = createUser("USER");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromCreatingAnAccountForAnotherUser() {
        User user = createUser("USER");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Sneaky\",\"accountType\":\"SAVINGS\",\"balance\":0}", bearerFor(user)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromUpdatingAnotherUsersAccount() {
        User user = createUser("USER");
        User target = createUser("USER");
        Account account = createAccount(target);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts/" + account.getId(), HttpMethod.PUT,
                new HttpEntity<>("{\"name\":\"Renamed\",\"accountType\":\"SAVINGS\"}", bearerFor(user)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromDeletingAnotherUsersAccount() {
        User user = createUser("USER");
        User target = createUser("USER");
        Account account = createAccount(target);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts/" + account.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(accountRepository.findById(account.getId())).isPresent();
    }

    @Test
    void admin_canListAnotherUsersAccounts() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(account.getId().toString());
    }

    @Test
    void admin_canCreateAnAccountForAnotherUser() throws Exception {
        // ADMIN holds ACCOUNT_CREATE per V16__rbac_roles_permissions.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts", HttpMethod.POST,
                new HttpEntity<>("{\"name\":\"Support Created\",\"accountType\":\"SAVINGS\",\"balance\":500}",
                        bearerFor(admin)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(response.getBody()).get("data");
        assertThat(created.get("name").asText()).isEqualTo("Support Created");
        assertThat(accountRepository.findById(UUID.fromString(created.get("id").asText())))
                .isPresent().get().extracting(Account::getUserId).isEqualTo(target.getId());
    }

    @Test
    void admin_canUpdateAnotherUsersAccount() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts/" + account.getId(), HttpMethod.PUT,
                new HttpEntity<>("{\"name\":\"Renamed By Support\",\"accountType\":\"SAVINGS\"}", bearerFor(admin)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).get("data").get("name").asText())
                .isEqualTo("Renamed By Support");
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getName())
                .isEqualTo("Renamed By Support");
    }

    @Test
    void admin_deletingAnAccount_softDeletesIt() {
        // ADMIN holds ACCOUNT_DELETE per V16__rbac_roles_permissions.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts/" + account.getId(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // AccountService.delete soft-deletes via @SQLDelete/@SQLRestriction -- findById no longer
        // sees the row through the normal (deleted_at IS NULL) query path.
        assertThat(accountRepository.findById(account.getId())).isEmpty();
    }

    @Test
    void deletingAnAccountTwice_returnsNotFoundOnTheSecondAttempt() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        Account account = createAccount(target);
        HttpHeaders headers = bearerFor(admin);
        String url = "/api/v1/admin/users/" + target.getId() + "/accounts/" + account.getId();

        ResponseEntity<String> first = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second = restTemplate.exchange(url, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        // OwnershipGuard.requireOwned() 404s once the row is invisible via the soft-delete
        // @SQLRestriction, same as deleting an id that never existed.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deletingANonexistentAccount_returnsNotFound() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/accounts/" + UUID.randomUUID(),
                HttpMethod.DELETE, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
