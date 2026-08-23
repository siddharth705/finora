package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin management of GLOBAL-scope category rules (RuleService.listGlobal/createGlobal/...,
 * AdminRuleController, V25__rule_manage_permission.sql) -- this was seed-data-only before this
 * pass and had zero test coverage of any kind (unit or IT) until now. Proves RULE_MANAGE gating,
 * the full CRUD round-trip, and the validation guard that stops an ASSIGN_CATEGORY rule from ever
 * being saved without a category name (see RuleService.validateRule's own doc comment on why that
 * specific combination is worth guarding against: it turns into an unhandled 500 far away from
 * where the bad rule was authored otherwise).
 */
class AdminRuleControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-rules-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Rules IT Test User");
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
    void plainUser_isForbiddenFromManagingGlobalRules() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canCreateUpdateAndDeleteAGlobalRule() throws Exception {
        // ADMIN holds RULE_MANAGE per V25__rule_manage_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"MERCHANT","operator":"CONTAINS","comparisonValue":"Swiggy",
                         "actionType":"ASSIGN_CATEGORY","actionValue":"Dining","priority":10}
                        """, headers),
                String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(createResponse.getBody()).get("data");
        assertThat(created.get("scope").asText()).isEqualTo("GLOBAL");
        assertThat(created.get("enabled").asBoolean()).isTrue();
        String ruleId = created.get("id").asText();

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).contains("Swiggy");

        ResponseEntity<String> disableResponse = restTemplate.exchange(
                "/api/v1/admin/rules/" + ruleId, HttpMethod.PUT,
                new HttpEntity<>("{\"enabled\":false}", headers), String.class);
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(disableResponse.getBody()).get("data").get("enabled").asBoolean()).isFalse();
        // Partial update -- fields not supplied (comparisonValue, actionValue, ...) must survive.
        assertThat(mapper.readTree(disableResponse.getBody()).get("data").get("comparisonValue").asText())
                .isEqualTo("Swiggy");

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/rules/" + ruleId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAnAssignCategoryRule_withNoActionValue_isRejected() {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"rent",
                         "actionType":"ASSIGN_CATEGORY"}
                        """, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void testEndpoint_returnsTrueWhenTheSampleFieldsWouldMatch() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/rules/test", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"MERCHANT","operator":"CONTAINS","comparisonValue":"Swiggy",
                         "sampleMerchant":"Swiggy Bangalore"}
                        """, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).get("data").get("matches").asBoolean()).isTrue();
    }

    @Test
    void testEndpoint_returnsFalseWhenTheSampleFieldsWouldNotMatch() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/rules/test", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"MERCHANT","operator":"CONTAINS","comparisonValue":"Swiggy",
                         "sampleMerchant":"Zomato Bangalore"}
                        """, headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).get("data").get("matches").asBoolean()).isFalse();
    }

    @Test
    void testEndpoint_evaluatesAmountOperatorsAgainstTheSampleAmount() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        // GT: sample 6000 against comparisonValue 5000 -- must match.
        ResponseEntity<String> gtResponse = restTemplate.exchange(
                "/api/v1/admin/rules/test", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"AMOUNT","operator":"GT","comparisonValue":"5000","sampleAmount":6000}
                        """, headers),
                String.class);
        assertThat(mapper.readTree(gtResponse.getBody()).get("data").get("matches").asBoolean()).isTrue();

        // Same sample amount against LT 5000 -- must NOT match.
        ResponseEntity<String> ltResponse = restTemplate.exchange(
                "/api/v1/admin/rules/test", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"AMOUNT","operator":"LT","comparisonValue":"5000","sampleAmount":6000}
                        """, headers),
                String.class);
        assertThat(mapper.readTree(ltResponse.getBody()).get("data").get("matches").asBoolean()).isFalse();
    }

    @Test
    void testEndpoint_doesNotPersistAnything() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        restTemplate.exchange(
                "/api/v1/admin/rules/test", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"never saved","sampleDescription":"never saved anywhere"}
                        """, headers),
                String.class);

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(listResponse.getBody()).doesNotContain("never saved");
    }

    @Test
    void updatingARule_toAnEmptyComparisonValue_isRejected() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"electricity",
                         "actionType":"MARK_SUBSCRIPTION"}
                        """, headers),
                String.class);
        String ruleId = mapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        // A blank comparisonValue would make CONTAINS match every transaction unconditionally --
        // validateRule() must reject this on update, the same way create() already does via
        // @NotBlank at the API boundary.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/rules/" + ruleId, HttpMethod.PUT,
                new HttpEntity<>("{\"comparisonValue\":\"\"}", headers), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /** Defense-in-depth fix: UpdateRequest.field/operator/actionType carried zero Bean Validation
     *  and the controller had no @Valid, the same invisible-to-FG-028 shape as the real
     *  AdminMerchantReviewController.merge NPE. category_rules.field is VARCHAR(20) -- an oversized
     *  value must fail cleanly at the API boundary. */
    @Test
    void updatingARule_withAnOversizedField_isRejectedAsValidationError() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"gym",
                         "actionType":"MARK_SUBSCRIPTION"}
                        """, headers),
                String.class);
        String ruleId = mapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        String tooLong = "\"" + "X".repeat(21) + "\"";
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/rules/" + ruleId, HttpMethod.PUT,
                new HttpEntity<>("{\"field\":" + tooLong + "}", headers), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(updateResponse.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains("field");
    }
}
