package com.finora.controller;

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
 * GET /api/v1/users/me/access is what the admin portal (frontend-admin/) calls right after login
 * to decide whether an account has any admin-relevant permission before showing the admin shell.
 * Proves a plain USER gets back an empty-of-admin-permissions response (not a 403 -- any
 * authenticated user can see their own access) while an ADMIN gets back the real permission set
 * seeded in V16/V24, so the frontend's gate has something real to check.
 */
class MeAccessControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("me-access-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Me Access IT Test User");
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
    void plainUser_getsOwnAccess_withNoAdminPermissions() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/access", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("AUDIT_VIEW", "USER_VIEW", "ROLE_MANAGE", "SYSTEM_SETTINGS");
    }

    @Test
    void admin_getsOwnAccess_includingSeededAdminPermissions() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/users/me/access", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("AUDIT_VIEW", "USER_VIEW", "PLATFORM_STATS_VIEW", "SYSTEM_SETTINGS");
        // ADMIN's seeded set deliberately excludes these (V16) -- confirms this endpoint reflects
        // the real permission grant, not just "is this user an admin at all."
        assertThat(response.getBody()).doesNotContain("ROLE_MANAGE", "PERMISSION_MANAGE");
    }
}
