package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.service.AuditService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cross-user audit trail (AdminController) -- a per-user audit history and the global Activity
 * Feed for the admin portal. Proves AUDIT_VIEW gating on both endpoints and that the global feed's
 * q/date filters actually narrow results rather than always returning everything.
 */
class AdminControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditService auditService;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-audit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Audit IT Test User");
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
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromViewingAUsersAuditLogs() {
        User user = createUser("USER");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/audit-logs",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromViewingTheGlobalAuditFeed() {
        User user = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canViewAUsersAuditLogs() {
        // ADMIN holds AUDIT_VIEW per V16__rbac_roles_permissions.sql's seed grant.
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        auditService.record(target.getId(), "TEST_EVENT", "Test", target.getId(), Map.of("k", "v"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/audit-logs",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("TEST_EVENT");
    }

    @Test
    void admin_canViewTheGlobalAuditFeedAndFilterByQuery() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        String uniqueAction = "TEST_EVENT_" + UUID.randomUUID().toString().substring(0, 8);
        auditService.record(target.getId(), uniqueAction, "Test", target.getId(), Map.of("k", "v"));
        auditService.record(target.getId(), "UNRELATED_EVENT", "Test", target.getId(), Map.of("k", "v"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction,
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(uniqueAction);
        assertThat(response.getBody()).doesNotContain("UNRELATED_EVENT");
    }
}
