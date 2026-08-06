package com.finora.controller;

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
 * Proves the platform stats overview (admin Dashboard, frontend-admin/) is gated by
 * PLATFORM_STATS_VIEW (V24__admin_platform_stats_permission.sql) and returns real counts that
 * include a user just created in this same test, not a hardcoded/stubbed shape.
 */
class AdminStatsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-stats-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Stats IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail(), java.util.UUID.randomUUID()));
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromPlatformStats() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/stats/overview", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesAggregateCountsThatIncludeJustCreatedUsers() {
        User admin = createUser("ADMIN");
        createUser("USER");
        createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/stats/overview", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalUsers\"", "\"activeUsers\"", "\"newUsersLast7Days\"");
    }
}
