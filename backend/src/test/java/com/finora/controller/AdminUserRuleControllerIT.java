package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin, support-assisted personal rule management on behalf of a specific user
 * (AdminUserRuleController) -- proves RULE_MANAGE gating, that the admin path reaches the exact
 * same RuleService the self-service API used, and that it stays ownership-scoped (an admin
 * acting on userA's path can't reach userB's rule, and can't touch a GLOBAL rule through this
 * per-user path at all).
 */
class AdminUserRuleControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-rule-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Rule IT Test User");
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
    void plainUser_isForbiddenFromManagingAnotherUsersRules() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canCreateUpdateAndDeleteATargetUsersRule() throws Exception {
        // ADMIN holds RULE_MANAGE per V25__rule_manage_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"Netflix",
                         "actionType":"MARK_SUBSCRIPTION"}
                        """, headers),
                String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(createResponse.getBody()).get("data");
        assertThat(created.get("scope").asText()).isEqualTo("USER");
        String ruleId = created.get("id").asText();

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(listResponse.getBody()).contains("Netflix");

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules/" + ruleId, HttpMethod.PUT,
                new HttpEntity<>("{\"enabled\":false}", headers), String.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(updateResponse.getBody()).get("data").get("enabled").asBoolean()).isFalse();

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules/" + ruleId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminActingOnUserA_cannotReachARuleThatBelongsToUserB() throws Exception {
        User admin = createUser("ADMIN");
        User userA = createUser("USER");
        User userB = createUser("USER");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + userB.getId() + "/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"Rent",
                         "actionType":"MARK_TRANSFER"}
                        """, headers),
                String.class);
        String userBsRuleId = mapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        // Same rule id, but the path names userA -- RuleService.getOwnedUserRule() must reject
        // this the same way it does for the self-service path.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + userA.getId() + "/rules/" + userBsRuleId, HttpMethod.PUT,
                new HttpEntity<>("{\"enabled\":false}", headers), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aGlobalRuleCannotBeReachedThroughThePerUserPath() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);

        // Create it as a GLOBAL rule via the platform-wide admin endpoint.
        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/rules", HttpMethod.POST,
                new HttpEntity<>("""
                        {"field":"DESCRIPTION","operator":"CONTAINS","comparisonValue":"Global Test Rule",
                         "actionType":"ADD_TAG","actionValue":"test"}
                        """, headers),
                String.class);
        String globalRuleId = mapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        // Same rule id, attempted through the per-user path -- getOwnedUserRule() rejects any
        // GLOBAL-scope rule regardless of which userId is in the path.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/rules/" + globalRuleId, HttpMethod.PUT,
                new HttpEntity<>("{\"enabled\":false}", headers), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
