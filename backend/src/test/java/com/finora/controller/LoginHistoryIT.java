package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
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
 * Phase 2 (audit/observability hardening) + user-security-center-proposal.md §3.1 self-service
 * login history -- a user's own record of USER_LOGIN/LOGIN_FAILED events, distinct from
 * AdminController's cross-user, RBAC-gated equivalent (see GlobalAuditLogIT).
 */
class LoginHistoryIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("login-history-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Login History IT Test User");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private void saveAuditEntry(UUID userId, String action) {
        AuditLog entry = new AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setEntityType("User");
        entry.setEntityId(userId);
        auditLogRepository.save(entry);
    }

    @Test
    void withoutABearerToken_isUnauthorized() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/login-history", HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void returnsTheCallersOwnLoginEvents_butNotAnotherUsersRow() throws Exception {
        User self = createUser();
        User other = createUser();
        saveAuditEntry(self.getId(), "USER_LOGIN");
        saveAuditEntry(self.getId(), "LOGIN_FAILED");
        saveAuditEntry(other.getId(), "USER_LOGIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/login-history", HttpMethod.GET, new HttpEntity<>(bearerFor(self)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data");
        assertThat(content).hasSize(2);
        for (JsonNode row : content) {
            assertThat(row.get("userId").asText()).isEqualTo(self.getId().toString());
        }
    }

    @Test
    void excludesNonLoginAuditActions() throws Exception {
        User self = createUser();
        saveAuditEntry(self.getId(), "USER_LOGIN");
        saveAuditEntry(self.getId(), "ACCOUNT_REACTIVATED");
        saveAuditEntry(self.getId(), "PASSWORD_CHANGED");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/login-history", HttpMethod.GET, new HttpEntity<>(bearerFor(self)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("action").asText()).isEqualTo("USER_LOGIN");
    }
}
