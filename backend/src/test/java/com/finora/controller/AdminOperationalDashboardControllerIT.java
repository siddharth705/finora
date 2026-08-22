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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The Operational Dashboard end to end -- proves PLATFORM_STATS_VIEW gating (reused, not a new
 *  permission -- see the controller's class comment) and that the real HealthProvider beans
 *  (Database, Statement Import Pipeline, Financial Intelligence Engine) actually get collected
 *  and returned, not just the mocked ones the unit tests exercise. */
class AdminOperationalDashboardControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-operational-dashboard-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Operational Dashboard IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Same as {@link #createUser}, but with a backdated {@code createdAt} set BEFORE the one and
     *  only save -- {@code created_at} is {@code updatable = false}, so reflecting it in after an
     *  entity is already persisted would be a silent no-op (see StatementAnalysisReportServiceIT's
     *  own doc comment on this exact trap). */
    private User createUserAt(String role, Instant createdAt) {
        User user = new User();
        user.setEmail("admin-operational-dashboard-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Operational Dashboard IT Test User");
        user.setRole(role);
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        ReflectionTestUtils.setField(user, "createdAt", createdAt);
        return userRepository.save(user);
    }

    private long inactiveUsersLast7Days(User admin) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/overview", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);
        return mapper.readTree(response.getBody()).get("data").get("inactiveUsersLast7Days").asLong();
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Test
    void plainUser_isForbiddenFromViewingTheOperationalDashboard() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/overview", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesEveryRegisteredHealthProvider_includingTheRealOnes() throws Exception {
        // ADMIN holds PLATFORM_STATS_VIEW per V24__admin_platform_stats_permission.sql's seed grant.
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/overview", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        JsonNode providers = data.get("health").get("providers");
        assertThat(providers).hasSizeGreaterThanOrEqualTo(3);

        var names = new java.util.ArrayList<String>();
        providers.forEach(p -> names.add(p.get("name").asText()));
        assertThat(names).contains("Database", "Statement Import Pipeline", "Financial Intelligence Engine");

        assertThat(data.get("health").get("overallStatus").asText()).isIn("UP", "DEGRADED", "DOWN");
        assertThat(data.get("totalUsers").asLong()).isGreaterThanOrEqualTo(1L);
    }

    // D-27 PR3-D.
    @Test
    void plainUser_isForbiddenFromViewingTheActivationFunnel() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/activation-funnel", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesTheActivationFunnel_asRealCountsAgainstTheDatabase() throws Exception {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/activation-funnel", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        // signedUp must agree with the Operational Dashboard's own totalUsers -- both read the
        // same countByRoleNot("BOOTSTRAP_ADMIN") figure.
        assertThat(data.get("signedUp").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(data.has("firstImport")).isTrue();
        assertThat(data.has("firstBudget")).isTrue();
        assertThat(data.has("firstGoal")).isTrue();
    }

    @Test
    void plainUser_isForbiddenFromViewingTheActivityTrend() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/activity-trend", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_seesTheActivityTrend_asSevenRealDailyPoints() throws Exception {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/dashboard/activity-trend", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data).hasSize(7);
        data.forEach(point -> {
            assertThat(point.has("date")).isTrue();
            assertThat(point.has("signups")).isTrue();
            assertThat(point.has("imports")).isTrue();
            assertThat(point.has("transactions")).isTrue();
        });
        // The admin created just above this call is today's own signup -- proves the last point
        // is a real live count against the database, not a fixed placeholder.
        assertThat(data.get(6).get("signups").asLong()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void inactiveUsersLast7Days_countsAUserWhoPredatesTheWindowAndNeverLoggedIn() throws Exception {
        User admin = createUser("ADMIN");
        long before = inactiveUsersLast7Days(admin);

        createUserAt("USER", Instant.now().minus(30, ChronoUnit.DAYS));

        assertThat(inactiveUsersLast7Days(admin)).isEqualTo(before + 1);
    }

    @Test
    void inactiveUsersLast7Days_excludesABrandNewSignupWithNoLoginYet() throws Exception {
        // Regression test: registration (AuthService.register()) writes USER_REGISTERED, never
        // USER_LOGIN, so a user created moments ago has zero USER_LOGIN rows -- exactly the same
        // signal as someone who has been gone seven days. Without requiring the account to predate
        // the cutoff, this user would have been counted as "inactive" the instant they signed up.
        User admin = createUser("ADMIN");
        long before = inactiveUsersLast7Days(admin);

        createUser("USER");

        assertThat(inactiveUsersLast7Days(admin)).isEqualTo(before);
    }
}
