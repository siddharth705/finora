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
 * Admin, support-assisted relationship (family/friend/own-account) tagging on behalf of a
 * specific user (AdminUserRelationshipController) -- proves RELATIONSHIP_MANAGE gating, that the
 * admin path reaches the exact same RelationshipService the self-service endpoint used to, and
 * that it stays ownership-scoped (an admin acting on userA's path can't reach userB's
 * relationship). Same shape of test as AdminUserRuleControllerIT/AdminUserMerchantControllerIT.
 */
class AdminUserRelationshipControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-user-relationship-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin User Relationship IT Test User");
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
    void plainUser_isForbiddenFromManagingAnotherUsersRelationships() {
        User user = createUser("USER");
        User target = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/relationships", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canCreateUpdateAndDeleteATargetUsersRelationship() throws Exception {
        // ADMIN holds RELATIONSHIP_MANAGE per V47__relationship_manage_permission.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/relationships", HttpMethod.POST,
                new HttpEntity<>("""
                        {"label":"Mom","relationshipType":"FAMILY",
                         "identifiers":[{"identifierType":"UPI_ID","identifierValue":"mom@upi"}]}
                        """, headers),
                String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode created = mapper.readTree(createResponse.getBody()).get("data");
        assertThat(created.get("label").asText()).isEqualTo("Mom");
        String relationshipId = created.get("id").asText();

        ResponseEntity<String> listResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/relationships", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(listResponse.getBody()).contains("Mom");

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/relationships/" + relationshipId, HttpMethod.PUT,
                new HttpEntity<>("{\"label\":\"Mother\"}", headers), String.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(updateResponse.getBody()).get("data").get("label").asText()).isEqualTo("Mother");

        ResponseEntity<String> deleteResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/relationships/" + relationshipId, HttpMethod.DELETE,
                new HttpEntity<>(headers), String.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminActingOnUserA_cannotReachARelationshipThatBelongsToUserB() throws Exception {
        User admin = createUser("ADMIN");
        User userA = createUser("USER");
        User userB = createUser("USER");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> createResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + userB.getId() + "/relationships", HttpMethod.POST,
                new HttpEntity<>("""
                        {"label":"Roommate","relationshipType":"FRIEND",
                         "identifiers":[{"identifierType":"UPI_ID","identifierValue":"roomie@upi"}]}
                        """, headers),
                String.class);
        String userBsRelationshipId = mapper.readTree(createResponse.getBody()).get("data").get("id").asText();

        // Same relationship id, but the path names userA -- RelationshipService's ownership check
        // must reject this the same way it does for the self-service path.
        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + userA.getId() + "/relationships/" + userBsRelationshipId, HttpMethod.PUT,
                new HttpEntity<>("{\"label\":\"Hijacked\"}", headers), String.class);

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
