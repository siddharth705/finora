package com.finora.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.RefreshTokenRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Support tickets at the HTTP layer — what {@code SupportTicketServiceTest}'s mocks cannot prove:
 * that {@code SUPPORT_MANAGE} really gates the admin controllers through Spring Security method
 * security, the cross-user attachment seam the plan's Phase 10 notes name explicitly — a multipart
 * upload, through persistence, to an authenticated download attempted by a *different* user,
 * expecting 404 — and, since Phase 5, that each of the four audit events really lands a row through
 * the real {@code AuditService}, not just that a mock was called with the right arguments. Unit
 * tests of each layer individually would pass while any of these seams leaks.
 */
class SupportTicketApiIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private com.finora.repository.UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private AuditLogRepository auditLogRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("support-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Support IT User");
        user.setRole(role);
        user.setAccountScope("ADMIN".equals(role) ? User.SCOPE_ADMIN : User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpHeaders jsonBearerFor(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // --- authorization -------------------------------------------------------------------------

    @Test
    void plainUser_isForbiddenFromTheAdminTicketQueue() {
        User user = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/support/tickets",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromTheAdminFeedbackList() {
        User user = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/feedback",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** V149 grants SUPPORT_MANAGE to ADMIN. Without that role_permissions row every admin 403s here. */
    @Test
    void adminWithTheGrantedPermission_canReadTheTicketQueue() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/admin/support/tickets",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Proves the {@code :status IS NULL OR ...} filter binding actually discriminates, not just
     * that the no-filter path returns 200 -- a null-parameter JPQL query can bind fine and still
     * silently ignore a real filter value if the predicate were written wrong.
     *
     * <p>Asserted by whether THIS test's ticket number appears in each page, not by {@code
     * totalElements} -- integration tests in this suite share one Postgres and rows leak between
     * classes (see {@code AbstractIntegrationTest}'s own notes), so a bare count is ordering- and
     * pollution-dependent in a way identity is not. {@code size=500} keeps the one ticket this test
     * cares about on the first page regardless of how many other tests' rows sort ahead of it.
     */
    @Test
    void adminTicketQueue_filtersByStatusAndCategory() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Technical issue ticket");

        boolean inMatchingPage = ticketAppearsIn(admin, "status=OPEN&category=TECHNICAL_ISSUE", ticketId);
        assertThat(inMatchingPage).isTrue();

        boolean inNonMatchingPage = ticketAppearsIn(admin, "status=OPEN&category=OTHER", ticketId);
        assertThat(inNonMatchingPage).isFalse();
    }

    private boolean ticketAppearsIn(User admin, String query, UUID ticketId) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/support/tickets?" + query + "&size=500", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).get("data").get("content");
        for (JsonNode entry : content) {
            if (entry.get("id").asText().equals(ticketId.toString())) return true;
        }
        return false;
    }

    // --- create + read-back, ownership -----------------------------------------------------------

    private UUID createTicket(User user, String subject) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "TECHNICAL_ISSUE");
        body.add("subject", subject);
        body.add("description", "It just spins on step 3.");
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("ticketNumber").asText()).matches("SUP-\\d{6}");
        return UUID.fromString(data.get("id").asText());
    }

    @Test
    void aUser_canReadBackTheirOwnTicket() throws Exception {
        User user = createUser("USER");
        UUID ticketId = createTicket(user, "Import is stuck");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(response.getBody()).get("data").get("subject").asText())
                .isEqualTo("Import is stuck");
    }

    @Test
    void aUser_cannotReadSomeoneElsesTicket() throws Exception {
        User owner = createUser("USER");
        User stranger = createUser("USER");
        UUID ticketId = createTicket(owner, "My private problem");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(bearerFor(stranger)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anAdmin_canReadAnyUsersTicketDetail_throughTheSharedRoute() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Someone else's ticket");

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/support/tickets/" + ticketId,
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminOpeningATicket_writesARealSupportTicketViewedRow() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Viewed by an admin");

        restTemplate.exchange("/api/v1/support/tickets/" + ticketId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        AuditLog row = onlyAuditRow(ticketId, "SUPPORT_TICKET_VIEWED");
        assertThat(row.getUserId()).isEqualTo(owner.getId());
        assertThat(row.getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
    }

    @Test
    void theOwnerOpeningTheirOwnTicket_writesNoViewedRow() throws Exception {
        User owner = createUser("USER");
        UUID ticketId = createTicket(owner, "Owner viewing their own ticket");

        restTemplate.exchange("/api/v1/support/tickets/" + ticketId, HttpMethod.GET,
                new HttpEntity<>(bearerFor(owner)), String.class);

        List<AuditLog> viewedRows = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_VIEWED")).toList();
        assertThat(viewedRows).isEmpty();
    }

    // --- the attachment seam: upload, then a different user is refused ---------------------------

    private HttpEntity<MultiValueMap<String, Object>> ticketWithAttachmentRequest(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("category", "OTHER");
        body.add("subject", "See attached");
        body.add("description", "Screenshot attached.");
        body.add("file", new ByteArrayResource("plain text attachment content".getBytes()) {
            @Override public String getFilename() { return "notes.txt"; }
        });
        return new HttpEntity<>(body, headers);
    }

    @Test
    void uploadedAttachment_ownerCanDownloadIt_aDifferentUserCannot() throws Exception {
        User owner = createUser("USER");
        User stranger = createUser("USER");

        ResponseEntity<String> createResponse = restTemplate.exchange("/api/v1/support/tickets",
                HttpMethod.POST, ticketWithAttachmentRequest(owner), String.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(createResponse.getBody()).get("data");
        UUID ticketId = UUID.fromString(data.get("id").asText());
        UUID attachmentId = UUID.fromString(data.get("attachments").get(0).get("id").asText());

        String downloadPath = "/api/v1/support/tickets/" + ticketId + "/attachments/" + attachmentId;

        ResponseEntity<byte[]> ownerDownload = restTemplate.exchange(downloadPath, HttpMethod.GET,
                new HttpEntity<>(bearerFor(owner)), byte[].class);
        assertThat(ownerDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(ownerDownload.getBody())).isEqualTo("plain text attachment content");

        ResponseEntity<String> strangerDownload = restTemplate.exchange(downloadPath, HttpMethod.GET,
                new HttpEntity<>(bearerFor(stranger)), String.class);
        assertThat(strangerDownload.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<byte[]> adminDownload = restTemplate.exchange(downloadPath, HttpMethod.GET,
                new HttpEntity<>(bearerFor(createUser("ADMIN"))), byte[].class);
        assertThat(adminDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anAdminDownloadingAnAttachment_writesARealAuditRow_theOwnerDownloadingItDoesNot() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");

        ResponseEntity<String> createResponse = restTemplate.exchange("/api/v1/support/tickets",
                HttpMethod.POST, ticketWithAttachmentRequest(owner), String.class);
        JsonNode data = mapper.readTree(createResponse.getBody()).get("data");
        UUID ticketId = UUID.fromString(data.get("id").asText());
        UUID attachmentId = UUID.fromString(data.get("attachments").get(0).get("id").asText());
        String downloadPath = "/api/v1/support/tickets/" + ticketId + "/attachments/" + attachmentId;

        restTemplate.exchange(downloadPath, HttpMethod.GET, new HttpEntity<>(bearerFor(owner)), byte[].class);
        List<AuditLog> afterOwnerDownload = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_ATTACHMENT_DOWNLOADED")).toList();
        assertThat(afterOwnerDownload).as("the owner downloading their own attachment is not audited").isEmpty();

        restTemplate.exchange(downloadPath, HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), byte[].class);
        AuditLog row = onlyAuditRow(ticketId, "SUPPORT_TICKET_ATTACHMENT_DOWNLOADED");
        assertThat(row.getUserId()).isEqualTo(owner.getId());
        assertThat(row.getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
        assertThat(row.getMetadata().get("attachmentId")).isEqualTo(attachmentId.toString());
        assertThat(row.getMetadata().get("filename")).isEqualTo("notes.txt");
    }

    // --- status transitions, over real HTTP -------------------------------------------------------

    @Test
    void anIllegalStatusTransition_returns409_overRealHttp() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Will be closed");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"CLOSED\"}", jsonBearerFor(admin)), String.class);

        ResponseEntity<String> illegalMove = restTemplate.exchange(
                "/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"OPEN\"}", jsonBearerFor(admin)), String.class);

        assertThat(illegalMove.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- claim is a takeover, not a conflict --------------------------------------------------

    @Test
    void claimingAnAlreadyClaimedTicket_succeedsRatherThanConflicting() throws Exception {
        User owner = createUser("USER");
        User firstAdmin = createUser("ADMIN");
        User secondAdmin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Two admins want this");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(bearerFor(firstAdmin)), String.class);

        ResponseEntity<String> takeover = restTemplate.exchange(
                "/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(bearerFor(secondAdmin)), String.class);

        assertThat(takeover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(takeover.getBody()).get("data").get("claimedByAdminId").asText())
                .isEqualTo(secondAdmin.getId().toString());
    }

    // --- feedback --------------------------------------------------------------------------------

    @Test
    void submittedFeedback_isVisibleToAnAdmin() throws Exception {
        User user = createUser("USER");
        User admin = createUser("ADMIN");
        String payload = """
                {"type":"BUG","context":"IMPORT_FLOW","message":"Import silently drops rows"}""";

        ResponseEntity<String> submit = restTemplate.exchange("/api/v1/feedback", HttpMethod.POST,
                new HttpEntity<>(payload, jsonBearerFor(user)), String.class);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> list = restTemplate.exchange("/api/v1/admin/feedback", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(list.getBody()).get("data").get("content");
        assertThat(content.isArray()).isTrue();
        boolean found = false;
        for (JsonNode entry : content) {
            if (entry.get("message").asText().equals("Import silently drops rows")) found = true;
        }
        assertThat(found).isTrue();
    }

    // --- audit (Phase 5): a real row lands, not just a mocked call ------------------------------

    private AuditLog onlyAuditRow(UUID ticketId, String action) {
        List<AuditLog> rows = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals(action)).toList();
        assertThat(rows).as("exactly one %s row for ticket %s", action, ticketId).hasSize(1);
        return rows.get(0);
    }

    @Test
    void creatingATicket_writesARealAuditRow_subjectIsTheUserThemselves() throws Exception {
        User user = createUser("USER");
        UUID ticketId = createTicket(user, "Audited on creation");

        AuditLog row = onlyAuditRow(ticketId, "SUPPORT_TICKET_CREATED");
        assertThat(row.getUserId()).isEqualTo(user.getId());
        assertThat(row.getEntityType()).isEqualTo("SupportTicket");
        assertThat(row.getMetadata().get("category")).isEqualTo("TECHNICAL_ISSUE");
    }

    @Test
    void changingStatus_writesARealAuditRow_subjectIsTheOwner_actorIsTheAdmin() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Status audited");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"IN_PROGRESS\"}", jsonBearerFor(admin)), String.class);

        AuditLog row = onlyAuditRow(ticketId, "SUPPORT_TICKET_STATUS_CHANGED");
        assertThat(row.getUserId()).isEqualTo(owner.getId());
        assertThat(row.getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
        assertThat(row.getMetadata().get("previousStatus")).isEqualTo("OPEN");
        assertThat(row.getMetadata().get("newStatus")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void anIllegalStatusTransition_writesNoAuditRowAtAll() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Rejected transition, no audit");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"CLOSED\"}", jsonBearerFor(admin)), String.class);
        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId, HttpMethod.PATCH,
                new HttpEntity<>("{\"status\":\"OPEN\"}", jsonBearerFor(admin)), String.class);

        List<AuditLog> statusRows = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_STATUS_CHANGED")).toList();
        // Exactly one: the legal OPEN -> CLOSED move. The rejected CLOSED -> OPEN attempt after it
        // writes nothing, since the 409 is thrown before the transaction reaches the audit call.
        assertThat(statusRows).hasSize(1);
        assertThat(statusRows.get(0).getMetadata().get("newStatus")).isEqualTo("CLOSED");
    }

    @Test
    void aTakeover_writesOneClaimedRow_withBothAdminIdsAndNoConflictRow() throws Exception {
        User owner = createUser("USER");
        User firstAdmin = createUser("ADMIN");
        User secondAdmin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Takeover audited");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(bearerFor(firstAdmin)), String.class);
        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(bearerFor(secondAdmin)), String.class);

        List<AuditLog> claimRows = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_CLAIMED")).toList();
        assertThat(claimRows).hasSize(2);

        AuditLog firstClaim = claimRows.get(0);
        assertThat(firstClaim.getMetadata().get("previousAdminId")).isNull();
        assertThat(firstClaim.getMetadata().get("newAdminId")).isEqualTo(firstAdmin.getId().toString());

        AuditLog takeover = claimRows.get(1);
        assertThat(takeover.getMetadata().get("actorId")).isEqualTo(secondAdmin.getId().toString());
        assertThat(takeover.getMetadata().get("previousAdminId")).isEqualTo(firstAdmin.getId().toString());
        assertThat(takeover.getMetadata().get("newAdminId")).isEqualTo(secondAdmin.getId().toString());
    }

    @Test
    void unclaiming_writesAClaimedRow_withANullNewAdminId() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Unclaim audited");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.POST,
                new HttpEntity<>(bearerFor(admin)), String.class);
        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/claim", HttpMethod.DELETE,
                new HttpEntity<>(bearerFor(admin)), String.class);

        List<AuditLog> claimRows = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(ticketId).stream()
                .filter(row -> row.getAction().equals("SUPPORT_TICKET_CLAIMED")).toList();
        assertThat(claimRows).hasSize(2);
        AuditLog unclaim = claimRows.get(1);
        assertThat(unclaim.getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
        assertThat(unclaim.getMetadata().get("previousAdminId")).isEqualTo(admin.getId().toString());
        assertThat(unclaim.getMetadata().get("newAdminId")).isNull();
    }

    @Test
    void addingANote_writesARealAuditRow_withoutTheNoteBodyInTheMetadata() throws Exception {
        User owner = createUser("USER");
        User admin = createUser("ADMIN");
        UUID ticketId = createTicket(owner, "Note audited");

        restTemplate.exchange("/api/v1/admin/support/tickets/" + ticketId + "/notes", HttpMethod.POST,
                new HttpEntity<>("{\"note\":\"Reproduced on Android 1.3.7, waiting on next deploy\"}",
                        jsonBearerFor(admin)), String.class);

        AuditLog row = onlyAuditRow(ticketId, "SUPPORT_TICKET_NOTE_ADDED");
        assertThat(row.getUserId()).isEqualTo(owner.getId());
        assertThat(row.getMetadata().get("actorId")).isEqualTo(admin.getId().toString());
        assertThat(row.getMetadata()).doesNotContainKey("note");
        assertThat(row.getMetadata().values())
                .as("the note body must never appear anywhere in the audit metadata")
                .noneMatch(value -> value != null && value.toString().contains("Android 1.3.7"));
    }
}
