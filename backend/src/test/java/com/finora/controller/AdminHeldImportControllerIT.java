package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The held-imports triage queue at the HTTP layer.
 *
 * <p>Four things this proves that {@code AdminHeldImportServiceTest} (mocked repositories) cannot:
 * the endpoints are really gated by V135's {@code IMPORT_TRIAGE_MANAGE} through Spring Security
 * method security rather than merely annotated with it; a detail view really does write an audit
 * row through the real {@code AuditService}; the raw parser error genuinely does not appear in the
 * list JSON a client receives; and a reprocess really does survive the {@code HELD_FOR_REVIEW}
 * status round-tripping through Postgres, including V134's rebuilt
 * {@code idx_import_jobs_live_content}.
 *
 * <p>That last one matters most here. The unique index and {@code ImportJob.Status.TERMINAL} have
 * to agree about what "live" means, and nothing below the database can prove they do.
 */
class AdminHeldImportControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-import-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Import IT User");
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

    /** A job in exactly the state ImportJobWorker's routing leaves an unclassified dead-letter in. */
    private ImportJob heldJob(UUID ownerId) {
        ImportJob job = new ImportJob(ownerId, "hdfc-june.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.markClaimed("worker", Instant.now());
        job.recordFailure("IllegalStateException: no header row found near \"Txn Date  Narration\"",
                "IllegalStateException", ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
        job.holdForReview("IllegalStateException", Instant.now());
        return importJobRepository.save(job);
    }

    // --- authorization ------------------------------------------------------------------------

    @Test
    void plainUser_isForbiddenFromTheQueue() {
        User user = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/held-imports",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromTheDetailView() {
        User user = createUser("USER");
        ImportJob job = heldJob(user.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_cannotReprocessSomeoneElsesStatement() {
        User user = createUser("USER");
        ImportJob job = heldJob(user.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/reprocess",
                HttpMethod.POST, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(importJobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    /** V135 grants IMPORT_TRIAGE_MANAGE to ADMIN. Without the role_permissions row the whole queue
     *  would 403 for every admin, which is the failure mode a permission with no grant produces. */
    @Test
    void adminWithTheGrantedPermission_canReadTheQueue() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/held-imports",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // --- the list/detail privacy split ---------------------------------------------------------

    /**
     * The raw parser error must not reach the unaudited list view.
     *
     * <p>A parser message quotes the input that defeated it, and on a bank statement that input is
     * a real person's financial data. Asserted against the JSON actually on the wire, not against
     * which components the DTO declares.
     */
    @Test
    void listResponse_carriesTheCuratedCodeButNotTheRawParserError() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        heldJob(owner.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports?size=100",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("IllegalStateException");
        assertThat(body)
                .as("statement content must not reach a page an operator leaves open")
                .doesNotContain("Txn Date")
                .doesNotContain("no header row found");
        assertThat(mapper.readTree(body)).isNotNull();
    }

    @Test
    void detailResponse_carriesTheRawErrorAnEngineerNeeds() throws Exception {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        ImportJob job = heldJob(owner.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).path("data");
        assertThat(data.path("lastError").asText()).contains("no header row found");
    }

    /** The privacy commitment the feature rests on, proved through the real AuditService. */
    @Test
    void openingAHeldStatement_writesAnAuditRow() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        ImportJob job = heldJob(owner.getId());

        restTemplate.exchange("/api/v1/admin/held-imports/" + job.getId(),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        List<AuditLog> entries = auditLogRepository.findAll().stream()
                .filter(a -> "HELD_IMPORT_VIEWED".equals(a.getAction()))
                .filter(a -> job.getId().equals(a.getEntityId()))
                .toList();
        assertThat(entries)
                .as("every view of a customer's statement is recorded against the admin who did it")
                .hasSize(1);
        assertThat(entries.get(0).getUserId()).isEqualTo(admin.getId());
    }

    // --- reprocess ------------------------------------------------------------------------------

    /**
     * The round trip nothing below Postgres can prove: HELD_FOR_REVIEW survives the status CHECK
     * constraint V134 rebuilt, and requeuing the job does not trip the unique index V134 also
     * rebuilt. If those two disagreed about which statuses count as live, this is where it shows.
     */
    @Test
    void reprocess_returnsAHeldJobToTheQueueAndAudits() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        ImportJob job = heldJob(owner.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/reprocess",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImportJob reloaded = importJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(reloaded.getAttemptCount()).isZero();
        assertThat(reloaded.wasHeldForReview())
                .as("the marker has to survive the reprocess, or nobody gets notified")
                .isTrue();
        assertThat(auditLogRepository.findAll().stream()
                .anyMatch(a -> "HELD_IMPORT_REPROCESSED".equals(a.getAction())
                        && job.getId().equals(a.getEntityId()))).isTrue();
    }

    /** Reprocessing something that is not held is a 409 naming the state, not a 500. */
    @Test
    void reprocessingAJobThatIsNotHeld_is409() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        ImportJob job = importJobRepository.save(
                new ImportJob(owner.getId(), "s.csv", "hash-" + UUID.randomUUID(), "objects/k", "CSV"));

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/reprocess",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("QUEUED");
    }

    /**
     * Two held jobs may legitimately share one (user, document) -- V134 excludes HELD_FOR_REVIEW
     * from idx_import_jobs_live_content precisely so a user told "no action needed" can re-upload
     * anyway. Requeuing both would make them both live and violate that index. Against a real
     * database this is the test that fails if the guard is removed; the mocked service test cannot
     * see the constraint at all.
     */
    @Test
    void reprocessAll_doesNotRequeueTwoHeldJobsForTheSameDocument() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        String sharedHash = "shared-hash-" + UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf", sharedHash,
                    "objects/key-" + UUID.randomUUID(), "PDF");
            job.markClaimed("worker", Instant.now());
            job.markClaimed("worker", Instant.now());
            job.recordFailure("IllegalStateException: no header row", "IllegalStateException",
                    ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT, Instant.now());
            job.holdForReview("IllegalStateException", Instant.now());
            importJobRepository.save(job);
        }

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/reprocess-all",
                HttpMethod.POST, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode())
                .as("a unique-index violation here would surface as a 500 on one operator click")
                .isEqualTo(HttpStatus.OK);
        long stillHeld = importJobRepository.findAll().stream()
                .filter(j -> sharedHash.equals(j.getContentHash()))
                .filter(j -> j.getStatus() == ImportJob.Status.HELD_FOR_REVIEW)
                .count();
        assertThat(stillHeld)
                .as("exactly one of the pair goes back on the queue; the other waits its turn")
                .isEqualTo(1);
    }

    // --- resolve --------------------------------------------------------------------------------

    @Test
    void resolve_landsTheJobInPlainFailedAndRecordsTheReason() {
        User admin = createUser("ADMIN");
        User owner = createUser("USER");
        ImportJob job = heldJob(owner.getId());

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-imports/" + job.getId() + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>("{\"reason\":\"scanned image with no text layer\"}", bearerFor(admin)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(importJobRepository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.FAILED);
        assertThat(auditLogRepository.findAll().stream()
                .anyMatch(a -> "HELD_IMPORT_RESOLVED".equals(a.getAction())
                        && job.getId().equals(a.getEntityId()))).isTrue();
    }
}
