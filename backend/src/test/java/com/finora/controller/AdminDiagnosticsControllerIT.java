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
 * Proves Platform Diagnostics is gated by PLATFORM_DIAGNOSTICS_VIEW (V34) specifically, not
 * SYSTEM_SETTINGS -- a plain USER is forbidden, an ADMIN (which V34 grants this permission to,
 * alongside its existing SYSTEM_SETTINGS) succeeds. See AdminDiagnosticsController's own class
 * doc for why this permission was split out.
 */
class AdminDiagnosticsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-diagnostics-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Diagnostics IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromPlatformDiagnostics() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/diagnostics", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesPlatformDiagnostics_viaTheNewDedicatedPermission() {
        // ADMIN doesn't get PLATFORM_DIAGNOSTICS_VIEW through any special-casing here -- V34
        // grants it to the ADMIN role directly, and AuthorizationService resolves that live
        // against the role's current permissions on every request, not a frozen snapshot from
        // whenever this user was created.
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/diagnostics", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"application\"", "\"runtime\"", "\"health\"", "\"configuration\"", "\"recentImports\"");
    }
}
