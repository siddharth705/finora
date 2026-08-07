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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves @PreAuthorize("hasRole('ADMIN')") on AdminController actually rejects a normal user
 * and actually allows an admin — not just that the annotation is present and compiles. It's
 * entirely possible to add @PreAuthorize and have it silently do nothing if method security
 * isn't enabled or the authority isn't populated correctly; this test would catch that.
 */
class AdminRbacIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("rbac-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("RBAC Test User");
        user.setRole(role);
        // Phone verification defaults to false and is now enforced server-side on every
        // protected endpoint (PhoneVerificationFilter) -- without this, both tests below would
        // be rejected before ever reaching the @PreAuthorize check this class exists to test,
        // which would make regularUser_isForbiddenFromAdminEndpoint pass for the wrong reason
        // and adminUser_canAccessAdminEndpoint fail outright.
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void regularUser_isForbiddenFromAdminEndpoint() {
        User user = createUser("USER");
        String token = TestSessions.accessTokenFor(jwtService, refreshTokens, user);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + user.getId() + "/audit-logs",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void adminUser_canAccessAdminEndpoint() {
        User admin = createUser("ADMIN");
        User targetUser = createUser("USER");
        String token = TestSessions.accessTokenFor(jwtService, refreshTokens, admin);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + targetUser.getId() + "/audit-logs",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
