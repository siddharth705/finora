package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admin portal's global (cross-user) audit feed -- AdminController.globalAuditLogs, distinct
 * from the existing per-user auditLogsForUser endpoint AdminRbacIT already covers. Registering a
 * user itself writes a USER_REGISTERED audit entry (AuthService.register), which is enough to
 * prove this endpoint surfaces real, paginated, platform-wide activity rather than being scoped
 * to one account.
 */
class GlobalAuditLogIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("global-audit-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Global Audit IT Test User");
        user.setRole(role);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtService.generateToken(user.getId(), user.getEmail()));
        return headers;
    }

    /** Writes a real AuditLog row directly rather than triggering one via a side-effecting flow
     *  (registration, login, ...) -- lets these Phase 5 filter tests control createdAt precisely
     *  (via reflection, since AuditLog has no public setter for it -- entries are meant to be
     *  timestamped at write time, not backdated in production code) without depending on some
     *  other endpoint's audit-writing behavior staying the same. */
    private void saveAuditEntry(UUID userId, String action, java.time.Instant createdAt) {
        com.finora.entity.AuditLog entry = new com.finora.entity.AuditLog();
        entry.setUserId(userId);
        entry.setAction(action);
        entry.setEntityType("TestEntity");
        org.springframework.test.util.ReflectionTestUtils.setField(entry, "createdAt", createdAt);
        auditLogRepository.save(entry);
    }

    @Test
    void plainUser_isForbiddenFromGlobalAuditFeed() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesPaginatedGlobalFeed() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?page=0&size=5",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"totalElements\"", "\"content\"");
    }

    @Test
    void search_filtersByQueryAcrossActionAndEntityType() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_ACTION_" + UUID.randomUUID();
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("action").asText()).isEqualTo(uniqueAction);
    }

    @Test
    void search_filtersByDateRange_excludingAnEntryOutsideIt() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_DATE_" + UUID.randomUUID();
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now());
        saveAuditEntry(admin.getId(), uniqueAction, java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.DAYS));

        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction + "&dateFrom=" + today + "&dateTo=" + today,
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        // Both entries share the unique action (so q alone would return 2) -- only the one dated
        // today should survive the dateFrom/dateTo=today range, proving the filter actually
        // excludes the 10-day-old entry rather than being ignored.
        assertThat(content).hasSize(1);
    }

    @Test
    void search_sortDirAsc_returnsOldestMatchingEntryFirst() throws Exception {
        User admin = createUser("ADMIN");
        String uniqueAction = "ZTEST_SORT_" + UUID.randomUUID();
        java.time.Instant older = java.time.Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS);
        java.time.Instant newer = java.time.Instant.now();
        saveAuditEntry(admin.getId(), uniqueAction, newer);
        saveAuditEntry(admin.getId(), uniqueAction, older);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/audit-logs?q=" + uniqueAction + "&sortDir=asc",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("createdAt").asText()).isLessThan(content.get(1).get("createdAt").asText());
    }
}
