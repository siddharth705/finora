package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.PlatformSettings;
import com.finora.entity.User;
import com.finora.repository.PlatformSettingsRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real platform-wide configuration (V27__platform_settings.sql / PlatformSettingsService /
 * AuthService) -- proves SYSTEM_SETTINGS gating on the settings endpoints themselves, and the
 * actual behavioral wiring: toggling registrationsEnabled off must make /auth/register reject
 * real requests, and adminCreateUser (the admin "create user" flow) must still work while it's
 * off.
 *
 * The platform_settings row is a process-wide singleton (see the migration's CHECK constraint)
 * and this test suite shares one Postgres container across every IT class (AbstractIntegrationTest),
 * so every test here restores registrationsEnabled to true in @AfterEach regardless of how the
 * test body ends -- otherwise a test here disabling registrations could make an unrelated test in
 * another class (e.g. AuthFlowIT's real /auth/register call) fail nondeterministically depending
 * on JUnit's class execution order.
 */
class PlatformSettingsControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformSettingsRepository platformSettingsRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void resetPlatformSettings() {
        PlatformSettings settings = platformSettingsRepository.findById((short) 1).orElseGet(PlatformSettings::new);
        settings.setRegistrationsEnabled(true);
        settings.setMaxFailedLoginAttempts(5);
        settings.setLockoutDurationMinutes(15);
        settings.setUpdatedAt(Instant.now());
        platformSettingsRepository.save(settings);
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("platform-settings-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Platform Settings IT Test User");
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
    void plainUser_isForbiddenFromReadingPlatformSettings() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/settings", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canReadAndUpdatePlatformSettings() throws Exception {
        // ADMIN holds SYSTEM_SETTINGS per V16__rbac_roles_permissions.sql's seed grant.
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> getResponse = restTemplate.exchange(
                "/api/v1/admin/settings", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(getResponse.getBody()).get("data");
        assertThat(data.get("registrationsEnabled").asBoolean()).isTrue();
        assertThat(data.get("maxFailedLoginAttempts").asInt()).isEqualTo(5);

        ResponseEntity<String> updateResponse = restTemplate.exchange(
                "/api/v1/admin/settings", HttpMethod.PUT,
                new HttpEntity<>("{\"maxFailedLoginAttempts\":3}", headers), String.class);
        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode updated = mapper.readTree(updateResponse.getBody()).get("data");
        assertThat(updated.get("maxFailedLoginAttempts").asInt()).isEqualTo(3);
        // Partial update -- fields not supplied must be untouched, not silently reset.
        assertThat(updated.get("registrationsEnabled").asBoolean()).isTrue();
        assertThat(updated.get("lockoutDurationMinutes").asInt()).isEqualTo(15);
    }

    @Test
    void disablingRegistrations_rejectsRealPublicRegisterRequests_butAdminCreateUserStillWorks() throws Exception {
        User admin = createUser("ADMIN");
        HttpHeaders headers = bearerFor(admin);

        ResponseEntity<String> disableResponse = restTemplate.exchange(
                "/api/v1/admin/settings", HttpMethod.PUT,
                new HttpEntity<>("{\"registrationsEnabled\":false}", headers), String.class);
        assertThat(disableResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders publicHeaders = new HttpHeaders();
        publicHeaders.setContentType(MediaType.APPLICATION_JSON);
        String blockedEmail = "blocked-" + System.currentTimeMillis() + "@example.com";
        String registerBody = """
                {"email": "%s", "password": "SecurePass123", "fullName": "Blocked User", "phoneNumber": "+919876500055"}
                """.formatted(blockedEmail);
        ResponseEntity<String> registerResponse = restTemplate.postForEntity(
                "/api/v1/auth/register", new HttpEntity<>(registerBody, publicHeaders), String.class);
        assertThat(registerResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(userRepository.findByEmailIgnoreCaseAndAccountScope(blockedEmail, "USER")).isEmpty();

        // USER_CREATE is SUPER_ADMIN-only (V16), so a support-assisted signup while registrations
        // are off needs a SUPER_ADMIN caller here, not the ADMIN used for the settings toggle.
        User superAdmin = createUser("SUPER_ADMIN");
        String adminCreateBody = """
                {"email": "support-created-%d@example.com", "password": "SecurePass123", "fullName": "Support Created", "phoneNumber": "+919876500066"}
                """.formatted(System.currentTimeMillis());
        ResponseEntity<String> adminCreateResponse = restTemplate.exchange(
                "/api/v1/admin/users", HttpMethod.POST,
                new HttpEntity<>(adminCreateBody, bearerFor(superAdmin)), String.class);
        assertThat(adminCreateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
