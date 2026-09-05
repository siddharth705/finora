package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementRepository;
import com.finora.repository.ImportJobRepository;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The telemetry endpoint's gate and its route, at the HTTP layer.
 *
 * <p>The route question is the one a unit test cannot answer: {@code GET
 * /api/v1/admin/held-statements/telemetry} must reach {@link AdminHeldStatementTelemetryController},
 * not fall through to {@link AdminHeldStatementController#detail}'s {@code /{heldId}} pattern with
 * "telemetry" bound as a held id. Verified here with a real request against a real Spring MVC
 * dispatcher, not assumed from reading the two {@code @RequestMapping}s side by side.
 */
class AdminHeldStatementTelemetryControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-stmt-telemetry-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Statement Telemetry IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> get(String path, User caller) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerFor(caller)), String.class);
    }

    private void seedHold(String heldId) {
        User owner = createUser("USER");
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        HeldStatement held = new HeldStatement(heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)");
        held.recordSnapshot("build-1", "NEEDS_ATTENTION", "NATIVE", false, List.of("COUNT_MISMATCH"));
        held = heldStatementRepository.save(held);
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
    }

    @Test
    void anAdministratorReadsTheAggregateSummary() throws Exception {
        seedHold("HLD-2026-700001");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response =
                get("/api/v1/admin/held-statements/telemetry", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).path("data");
        // This is the concrete proof the route resolved to the telemetry controller and not to
        // AdminHeldStatementController.detail("telemetry") -- that method's response shape has no
        // "totalHolds" field at all, and a 404/500 from treating "telemetry" as a held id would
        // fail the status assertion above first.
        assertThat(data.has("totalHolds")).isTrue();
        assertThat(data.path("totalHolds").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(data.has("byCategory")).isTrue();
    }

    @Test
    void aConsumerAccountCannotReadTelemetry() {
        // Without @PreAuthorize the admin path rule alone would leave this open to every
        // logged-in consumer account -- the same gate every PLATFORM_DIAGNOSTICS_VIEW sibling
        // proves for itself.
        User consumer = createUser("USER");
        consumer.setAccountScope(User.SCOPE_USER);
        userRepository.save(consumer);

        ResponseEntity<String> response =
                get("/api/v1/admin/held-statements/telemetry", consumer);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void theHeldStatementDetailRouteStillResolvesAHeldIdNamedLikeAnythingElse() {
        // The other half of the route-collision question: confirms adding the sibling controller
        // did not accidentally break AdminHeldStatementController's own /{heldId} pattern for a
        // real held id.
        seedHold("HLD-2026-700002");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response =
                get("/api/v1/admin/held-statements/HLD-2026-700002", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
