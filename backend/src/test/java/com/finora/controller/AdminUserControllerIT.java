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
 * The admin Users directory (frontend-admin/) -- list/search, detail, suspend, reactivate. Proves
 * both the USER_VIEW/USER_DELETE permission gating (a plain USER is rejected) and the actual
 * suspend/reactivate state transitions, not just that the endpoints return 200.
 */
class AdminUserControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-users-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Users IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true); // see AdminRbacIT for why this must be set
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromListingUsers() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canListAndSearchUsers() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users?q=" + target.getEmail() + "&page=0&size=20",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(target.getEmail());
    }

    @Test
    void admin_canGetUserDetail() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(target.getEmail(), "\"accountCount\"", "\"transactionCount\"");
    }

    @Test
    void admin_canSuspendThenReactivateUser() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        ResponseEntity<String> suspendResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/suspend",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(suspendResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(target.getId()).orElseThrow().getStatus()).isEqualTo("SUSPENDED");

        ResponseEntity<String> reactivateResponse = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/reactivate",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(reactivateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(target.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void admin_cannotSuspendOwnAccount() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/users/" + admin.getId() + "/suspend",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(userRepository.findById(admin.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void suspendingAlreadySuspendedUser_isIdempotent() {
        User admin = createUser("ADMIN");
        User target = createUser("USER");

        restTemplate.exchange("/api/v1/admin/users/" + target.getId() + "/suspend",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);
        ResponseEntity<String> secondSuspend = restTemplate.exchange(
                "/api/v1/admin/users/" + target.getId() + "/suspend",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(secondSuspend.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepository.findById(target.getId()).orElseThrow().getStatus()).isEqualTo("SUSPENDED");
    }
}
