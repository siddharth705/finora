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
 * Admin CRUD for custom banks (V26__custom_banks.sql / BankManagementService / AdminBankController)
 * -- proves BANK_MANAGE gating, that a custom bank id can't collide with a built-in BankRegistry
 * entry or another custom bank, and that deleting a bank still assigned to an account is blocked
 * (the same "can't orphan a foreign key" shape as the role/permission delete guards).
 */
class AdminBankControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-banks-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Banks IT Test User");
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

    @Test
    void plainUser_isForbiddenFromManagingBanks() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canCreateUpdateAndDeleteACustomBank() throws Exception {
        // ADMIN holds BANK_MANAGE per V16__rbac_roles_permissions.sql's seed grant.
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        String bankId = "IOB" + System.currentTimeMillis() % 100000;

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/banks", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"" + bankId + "\",\"officialName\":\"Indian Overseas Bank\",\"shortName\":\"IOB\"}", headers),
                String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(createResponse.getBody()).get("data");
        assertThat(created.get("id").asText()).isEqualTo(bankId);

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/banks/" + bankId, HttpMethod.PUT,
                new HttpEntity<>("{\"shortName\":\"IOB Updated\"}", headers), String.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(updateResponse.getBody()).get("data").get("shortName").asText())
                .isEqualTo("IOB Updated");

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/banks/" + bankId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingABank_withAnIdThatCollidesWithABuiltInBank_isRejected() {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        // HDFC is one of BankRegistry's built-in ~40 banks -- reusing its id must be rejected
        // even case-insensitively, since createCustom() uppercases before comparing.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"hdfc\",\"officialName\":\"Fake HDFC\",\"shortName\":\"Fake\"}", headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deletingACustomBank_stillAssignedToAnAccount_isRejected() throws Exception {
        User admin = createUser("ADMIN");
        User accountOwner = createUser("USER");
        HttpHeaders headers = bearerFor(admin);
        String bankId = "CUB" + System.currentTimeMillis() % 100000;

        restTemplate.exchange("/api/v1/admin/banks", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"" + bankId + "\",\"officialName\":\"Custom Bank\",\"shortName\":\"Custom\"}", headers),
                String.class);

        Account account = new Account();
        account.setUserId(accountOwner.getId());
        account.setName("Test Savings");
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        account.setBankId(bankId);
        accountRepository.save(account);

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/banks/" + bankId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void audit_returnsBankCreatedAndUpdatedEntries_inNewestFirstOrder() throws Exception {
        // Admin Portal Phase 4 -- EntityDrawer's Audit tab for the Banks page reference
        // implementation. Proves the native metadata->>'bankId' query (AuditLogRepository
        // .findByBankIdInMetadata) actually finds the real BANK_CREATED/BANK_UPDATED rows this
        // create+update flow writes, not a placeholder.
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        String bankId = "AUD" + System.currentTimeMillis() % 100000;

        restTemplate.exchange("/api/v1/admin/banks", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"" + bankId + "\",\"officialName\":\"Audit Test Bank\",\"shortName\":\"Audit\"}", headers),
                String.class);
        restTemplate.exchange("/api/v1/admin/banks/" + bankId, HttpMethod.PUT,
                new HttpEntity<>("{\"shortName\":\"Audit Updated\"}", headers), String.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks/" + bankId + "/audit", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data).hasSize(2);
        // Newest first -- the update (second write) appears before the create (first write).
        assertThat(data.get(0).get("action").asText()).isEqualTo("BANK_UPDATED");
        assertThat(data.get(1).get("action").asText()).isEqualTo("BANK_CREATED");
    }

    @Test
    void audit_returnsAnEmptyListForABankWithNoRecordedHistory() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        // A built-in BankRegistry id (never went through createCustom, so never audited) --
        // "no history yet" is a normal empty state for this tab, not a 404.
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks/hdfc/audit", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).isEmpty();
    }

    /**
     * {@code BankManagementService.listCustom()}/{@code resolve()}/{@code listAll()} now read
     * through {@code CustomBankLookup}'s cache (Caffeine, TTL 10 min -- see {@code CacheConfig})
     * instead of querying {@code bankRepository} directly on every call. This is the regression
     * that would catch a broken {@code @CacheEvict}: prime the cache with a GET before each
     * mutation, then assert the very next GET reflects it -- a stale cache would still show the
     * pre-mutation list here, where {@code admin_canCreateUpdateAndDeleteACustomBank} above would
     * not have noticed, since it only reads the mutation's own response, never a separate list read.
     */
    @Test
    void theListEndpoint_reflectsCreateUpdateAndDelete_immediately_notAfterTheCacheTtl() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);
        String bankId = "CACHE" + System.currentTimeMillis() % 100000;

        // Primes the cache with the list as it stood before this bank ever existed.
        JsonNode beforeCreate = list(headers);
        assertThat(containsBankId(beforeCreate, bankId)).isFalse();

        restTemplate.exchange("/api/v1/admin/banks", HttpMethod.POST,
                new HttpEntity<>("{\"id\":\"" + bankId + "\",\"officialName\":\"Cache Test Bank\",\"shortName\":\"Cache\"}", headers),
                String.class);
        JsonNode afterCreate = list(headers);
        assertThat(containsBankId(afterCreate, bankId))
                .as("a stale cache would still show the pre-create list here")
                .isTrue();

        restTemplate.exchange("/api/v1/admin/banks/" + bankId, HttpMethod.PUT,
                new HttpEntity<>("{\"shortName\":\"Cache Renamed\"}", headers), String.class);
        JsonNode afterUpdate = list(headers);
        assertThat(shortNameOf(afterUpdate, bankId))
                .as("a stale cache would still show the pre-update shortName here")
                .isEqualTo("Cache Renamed");

        restTemplate.exchange("/api/v1/admin/banks/" + bankId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        JsonNode afterDelete = list(headers);
        assertThat(containsBankId(afterDelete, bankId))
                .as("a stale cache would still show the deleted bank here")
                .isFalse();
    }

    private JsonNode list(HttpHeaders headers) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return mapper.readTree(response.getBody()).get("data").get("content");
    }

    private boolean containsBankId(JsonNode list, String bankId) {
        for (JsonNode bank : list) {
            if (bank.get("id").asText().equals(bankId)) return true;
        }
        return false;
    }

    private String shortNameOf(JsonNode list, String bankId) {
        for (JsonNode bank : list) {
            if (bank.get("id").asText().equals(bankId)) return bank.get("shortName").asText();
        }
        throw new AssertionError("Bank " + bankId + " not found in list");
    }

    @Test
    void plainUser_isForbiddenFromViewingBankAudit() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks/hdfc/audit", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
