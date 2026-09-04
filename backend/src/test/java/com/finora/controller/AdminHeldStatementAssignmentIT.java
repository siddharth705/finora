package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.repository.HeldStatementEventRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assignment actions pulled forward from brief Phase 6: Assign to Me, Start Investigation, Add
 * Notes. The entity transitions and their guards already exist and are tested at that level (Plan
 * 1 Task 4) -- this proves the service, the endpoints and the audit trail around them, the same
 * split {@code AdminHeldStatementControllerIT} draws for approve/reject.
 */
class AdminHeldStatementAssignmentIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementEventRepository eventRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-assign-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Assignment IT User");
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

    /** A job in exactly the state the worker's trust gate leaves a held import in. */
    private HeldStatement seedHold(String heldId) {
        User owner = createUser("USER");
        ImportJob job = new ImportJob(owner.getId(), "hdfc-june.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        UUID sessionId = UUID.randomUUID();
        job.holdForTrustReview(sessionId, null, Instant.now());
        importJobRepository.save(job);

        HeldStatement held = heldStatementRepository.save(new HeldStatement(
                heldId, job.getId(), owner.getId(), job.getObjectKey(),
                "Printed and parsed transaction count disagree (ROW_GROUPING)"));
        job.holdForTrustReview(sessionId, held.getId(), Instant.now());
        importJobRepository.save(job);
        return held;
    }

    private ResponseEntity<String> post(String path, User admin, String body) {
        return restTemplate.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, bearerFor(admin)), String.class);
    }

    // ------------------------------------------------------------------------------------ assign

    /** The common case: an operator picking up a hold must not have to type their own id. */
    @Test
    void assignDefaultsToTheCallingAdmin() {
        HeldStatement held = seedHold("HLD-2026-300001");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response =
                post("/api/v1/admin/held-statements/HLD-2026-300001/assign", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HeldStatement reloaded = heldStatementRepository.findById(held.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.ASSIGNED);
        assertThat(reloaded.getAssignedEngineerId()).isEqualTo(admin.getId());
    }

    /** An explicit engineerId assigns to someone else, not the caller. */
    @Test
    void assignCanNameAnotherEngineer() {
        seedHold("HLD-2026-300002");
        User admin = createUser("ADMIN");
        User engineer = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-300002/assign", admin,
                "{\"engineerId\":\"" + engineer.getId() + "\"}");

        assertThat(heldStatementRepository.findByHeldId("HLD-2026-300002").orElseThrow()
                .getAssignedEngineerId()).isEqualTo(engineer.getId());
    }

    /** Reassignment before resolution is legitimate -- HeldStatementTest already pins that on the
     *  entity; this pins that the endpoint allows it. */
    @Test
    void anUnresolvedHoldCanBeReassigned() {
        seedHold("HLD-2026-300003");
        User admin = createUser("ADMIN");
        User first = createUser("ADMIN");
        User second = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-300003/assign", admin,
                "{\"engineerId\":\"" + first.getId() + "\"}");
        ResponseEntity<String> reassign = post(
                "/api/v1/admin/held-statements/HLD-2026-300003/assign", admin,
                "{\"engineerId\":\"" + second.getId() + "\"}");

        assertThat(reassign.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findByHeldId("HLD-2026-300003").orElseThrow()
                .getAssignedEngineerId()).isEqualTo(second.getId());
    }

    /** The guards Plan 1 added must survive being reached over HTTP, as a 409 rather than a 500. */
    @Test
    void aResolvedHoldRefusesAssignmentWithAConflict() {
        seedHold("HLD-2026-300004");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-300004/approve", admin, "{}");

        ResponseEntity<String> response =
                post("/api/v1/admin/held-statements/HLD-2026-300004/assign", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("IMPORTED");
    }

    @Test
    void everyAssignmentIsAudited() {
        HeldStatement held = seedHold("HLD-2026-300005");
        User admin = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-300005/assign", admin, "{}");

        assertThat(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId()))
                .anyMatch(e -> "ASSIGNED".equals(e.getEventType()) && admin.getId().equals(e.getActorId()));
    }

    // -------------------------------------------------------------------------------- investigate

    @Test
    void investigateMovesAnAssignedHoldForward() {
        seedHold("HLD-2026-300006");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-300006/assign", admin, "{}");

        ResponseEntity<String> response =
                post("/api/v1/admin/held-statements/HLD-2026-300006/investigate", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findByHeldId("HLD-2026-300006").orElseThrow().getStatus())
                .isEqualTo(HeldStatement.Status.INVESTIGATING);
    }

    @Test
    void investigatingAResolvedHoldIsAConflict() {
        seedHold("HLD-2026-300007");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-300007/reject", admin, "{}");

        ResponseEntity<String> response =
                post("/api/v1/admin/held-statements/HLD-2026-300007/investigate", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ------------------------------------------------------------------------------------- notes

    /** Notes replace the engineer's own write-up; the history of what it said lives in the events,
     *  which this checks is where it actually landed. */
    @Test
    void addingNotesRecordsAnEventCarryingThem() throws Exception {
        HeldStatement held = seedHold("HLD-2026-300008");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post("/api/v1/admin/held-statements/HLD-2026-300008/notes",
                admin, "{\"notes\":\"Section 2's closing balance does not match the printed summary.\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findById(held.getId()).orElseThrow().getEngineerNotes())
                .contains("closing balance");

        assertThat(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId()))
                .anyMatch(e -> "NOTES_UPDATED".equals(e.getEventType())
                        && e.getNotes() != null && e.getNotes().contains("closing balance")
                        && admin.getId().equals(e.getActorId()));

        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("data").path("engineerNotes").asText()).contains("closing balance");
    }

    /** Each call replaces the notes wholesale -- there is one column, not a running log. */
    @Test
    void addingNotesTwiceReplacesTheFirstSet() {
        seedHold("HLD-2026-300009");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-300009/notes", admin, "{\"notes\":\"First pass.\"}");

        post("/api/v1/admin/held-statements/HLD-2026-300009/notes", admin,
                "{\"notes\":\"Second pass, supersedes the first.\"}");

        assertThat(heldStatementRepository.findByHeldId("HLD-2026-300009").orElseThrow()
                .getEngineerNotes()).isEqualTo("Second pass, supersedes the first.");
    }

    /** Notes are deliberately not guarded by refuseIfResolved -- a closing note after a decision
     *  is legitimate, not a state-machine violation. */
    @Test
    void notesCanStillBeAddedAfterResolution() {
        seedHold("HLD-2026-300010");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-300010/approve", admin, "{}");

        ResponseEntity<String> response = post("/api/v1/admin/held-statements/HLD-2026-300010/notes",
                admin, "{\"notes\":\"Closing note after approval.\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void assignRequiresAuthentication() {
        seedHold("HLD-2026-300011");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-statements/HLD-2026-300011/assign", HttpMethod.POST,
                new HttpEntity<>("{}"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
