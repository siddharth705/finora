package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
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
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-banks-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Banks IT Test User");
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

    @Test
    void plainUser_isForbiddenFromViewingBankAudit() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/banks/hdfc/audit", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
