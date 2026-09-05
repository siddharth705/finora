package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.exception.ErrorCode;
import com.finora.imports.analysis.ImportVerificationFinding;
import com.finora.imports.analysis.ImportVerificationFindingRepository;
import com.finora.imports.storage.ContentAddress;
import com.finora.imports.storage.StatementStorage;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import com.finora.notification.repository.NotificationRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust-review queue at the HTTP layer.
 *
 * <p>Three things this proves that no unit test can: the endpoints are really gated by V144's
 * {@code TRUST_REVIEW_MANAGE} through Spring Security method security rather than merely annotated
 * with it; approving really does move BOTH the hold and its job, through Postgres, including the
 * {@code HELD_FOR_TRUST_REVIEW} status round-tripping the rebuilt status CHECK; and rejecting
 * really does reach FAILED -- which {@code recordFailure} cannot do to a terminal job, and which
 * is the bug this task existed to avoid.
 */
@TestPropertySource(properties = {
        "app.statement-storage.provider=filesystem",
        "app.statement-storage.filesystem.root=${java.io.tmpdir}/finora-held-statement-controller-it"
})
class AdminHeldStatementControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private HeldStatementEventRepository eventRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementStorage storage;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] CLEAN_CSV = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);

    /** Same shape as {@link #seedHold}, but with real, storage-backed CSV bytes -- {@code
     *  rerun-parser} actually re-parses them (BH-045: {@code ImportJob.getFileContent()} always
     *  returns null), unlike every other endpoint this file otherwise tests. */
    private HeldStatement seedHoldWithRealBytes(String heldId) {
        User owner = createUser("USER");
        ContentAddress address = storage.store(CLEAN_CSV);
        ImportJob job = new ImportJob(owner.getId(), "statement.csv", address.hash(), address.key(), "CSV");
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

    private User createUser(String role) {
        User user = new User();
        user.setEmail("held-stmt-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Statement IT User");
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

    private ResponseEntity<String> get(String path, User admin) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);
    }

    // ---------------------------------------------------------------------------- the gate

    @Test
    void listRequiresAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/admin/held-statements", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * A signed-in ordinary user is not an operator.
     *
     * <p>The permission is what stands between "anyone with an account" and a queue of other
     * people's bank statements, and an annotation that is present but not enforced looks identical
     * in review to one that is.
     */
    @Test
    void listRefusesAUserWithoutTheTrustReviewPermission() {
        User ordinary = createUser("USER");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-statements", HttpMethod.GET,
                new HttpEntity<>(bearerFor(ordinary)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void anAdminCanSeeTheQueue() throws Exception {
        seedHold("HLD-2026-100001");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-statements", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = mapper.readTree(response.getBody()).path("data").path("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.findValuesAsText("heldId")).contains("HLD-2026-100001");
        // The queue is a worklist, not a statement viewer: it names why the hold fired, and
        // carries none of the document it fired on.
        assertThat(content.findValuesAsText("triggerSummary").getFirst()).contains("count");
        assertThat(response.getBody()).doesNotContain("statementObjectKey");
    }

    /** V150 / the admin-portal queue's Bank and User columns -- both fields have to actually reach
     *  the wire, not just exist on the entity. Found by heldId rather than indexed, because this
     *  class does not roll back between tests and the queue lists every open hold, not just this
     *  one. */
    @Test
    void theQueueCarriesTheUserAndTheSnapshottedBankName() throws Exception {
        HeldStatement held = seedHold("HLD-2026-100012");
        held.recordBank("HDFC Bank");
        heldStatementRepository.save(held);
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-statements?size=200", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        JsonNode content = mapper.readTree(response.getBody()).path("data").path("content");
        JsonNode row = java.util.stream.StreamSupport.stream(content.spliterator(), false)
                .filter(n -> "HLD-2026-100012".equals(n.path("heldId").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("HLD-2026-100012 not in the queue"));
        assertThat(row.path("userId").asText()).isEqualTo(held.getUserId().toString());
        assertThat(row.path("bankName").asText()).isEqualTo("HDFC Bank");
    }

    // ------------------------------------------------------------------------------- detail view

    /**
     * The operator has to see the numbers, not our sentence about them: "the counts disagree" is
     * not enough to judge whether the extraction is wrong. These are the same rows {@code
     * ImportVerificationRecorder} already writes on every held import -- reused, not re-derived.
     */
    @Test
    void detailCarriesTheFindingDetailsBehindTheTriggerSummary() throws Exception {
        HeldStatement held = seedHold("HLD-2026-100009");
        findingRepository.save(ImportVerificationFinding.forJob(held.getImportJobId(), 0,
                "SUMMARY_TOTALS", "FAILED",
                "{\"printedCreditCount\":80,\"parsedCreditCount\":79}"));
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-100009", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode finding = mapper.readTree(response.getBody()).path("data").path("findings").get(0);
        assertThat(finding.path("rule").asText()).isEqualTo("SUMMARY_TOTALS");
        assertThat(finding.path("outcome").asText()).isEqualTo("FAILED");
        assertThat(finding.path("details").path("printedCreditCount").asInt()).isEqualTo(80);
        assertThat(finding.path("details").path("parsedCreditCount").asInt()).isEqualTo(79);
    }

    /**
     * The timeline is the audit history, oldest first -- it is read as a narrative.
     *
     * <p>{@code seedHold} in this file saves the row directly through the repository, not through
     * {@code HeldStatementService.createHold}, so it writes no {@code HELD_CREATED} event of its
     * own -- this test seeds both events itself rather than assume one that was never written.
     */
    @Test
    void detailCarriesTheEventTimelineOldestFirst() throws Exception {
        HeldStatement held = seedHold("HLD-2026-100010");
        User admin = createUser("ADMIN");
        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, "HELD", "counts disagree"));
        eventRepository.save(new HeldStatementEvent(held.getId(), admin.getId(), "ASSIGNED",
                "HELD", "ASSIGNED", null));

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-100010", admin);

        JsonNode timeline = mapper.readTree(response.getBody()).path("data").path("timeline");
        assertThat(timeline.findValuesAsText("eventType"))
                .containsExactly("HELD_CREATED", "ASSIGNED");
    }

    /** Still no statement content: the detail view explains a decision, it does not display the
     *  document. That is what the download endpoint is for, and it is gated differently. */
    @Test
    void detailCarriesNoStatementContentOrObjectKey() throws Exception {
        seedHold("HLD-2026-100011");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = get("/api/v1/admin/held-statements/HLD-2026-100011", admin);

        assertThat(response.getBody()).doesNotContain("statementObjectKey");
        assertThat(response.getBody()).doesNotContain("objects/key-");
    }

    // ------------------------------------------------------------------------ approve / reject

    @Test
    void approveReleasesTheJobAndMarksTheHoldImported() {
        HeldStatement held = seedHold("HLD-2026-100002");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/HLD-2026-100002/approve", admin,
                "{\"note\":\"Counts reconcile once the carried-forward row is excluded.\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(HeldStatement.Status.IMPORTED);
        ImportJob job = importJobRepository.findById(held.getImportJobId()).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
        assertThat(job.getImportSessionId())
                .as("the approved rows are the ones the user gets")
                .isNotNull();
    }

    /**
     * The frontend sends {@code falsePositive} as a genuine JSON boolean (`{"falsePositive":
     * true}`), never as a quoted string -- but the controller declares its request body as {@code
     * Map<String, String>}, so this is really asking whether Jackson's default scalar coercion
     * turns a JSON boolean into the Java string {@code "true"} before {@code Boolean.valueOf} ever
     * sees it. Nothing before this test exercised that path: the service-level test for this same
     * behaviour calls {@code HeldStatementService.approve} directly, bypassing the controller's
     * JSON deserialization entirely. Verified here with a real HTTP request rather than assumed.
     */
    @Test
    void approveAcceptsFalsePositiveAsARealJsonBooleanNotAQuotedString() {
        HeldStatement held = seedHold("HLD-2026-100013");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/HLD-2026-100013/approve", admin,
                "{\"falsePositive\":true}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findById(held.getId()).orElseThrow().getFalsePositive())
                .isTrue();
    }

    /**
     * The promise the held-state copy already made the user: "we'll notify you once it's ready".
     *
     * <p>Nothing else can keep it. The worker's own {@code notifyIfPreviouslyHeld} gates on {@code
     * wasHeldForReview}, which a trust hold deliberately never sets, so without an explicit
     * notification here the import would quietly become available and nobody would be told. Every
     * other assertion in this class would still pass while that promise was silently broken --
     * which is exactly how it was missed the first time.
     */
    @Test
    void approvingTellsTheUserTheirStatementIsReady() {
        HeldStatement held = seedHold("HLD-2026-100007");
        User admin = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-100007/approve", admin, "{}");

        // NotificationService suffixes the caller's key with the channel, so one request becomes
        // one row per enabled channel.
        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_READY_" + held.getImportJobId() + ":PUSH"))
                .as("the user was promised they would be told")
                .isPresent();
    }

    /** Rejection stays silent, matching every other import failure -- and must not tell somebody
     *  their statement is ready when it never will be. */
    @Test
    void rejectingDoesNotClaimTheStatementIsReady() {
        HeldStatement held = seedHold("HLD-2026-100008");
        User admin = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-100008/reject", admin, "{}");

        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_READY_" + held.getImportJobId() + ":PUSH")).isEmpty();
        assertThat(notificationRepository.findByNotificationKey(
                "IMPORT_READY_" + held.getImportJobId() + ":EMAIL")).isEmpty();
    }

    /**
     * The reason this task needed its own job transition.
     *
     * <p>{@code recordFailure} returns ALREADY_FINISHED on a terminal job, and
     * HELD_FOR_TRUST_REVIEW is terminal -- so the obvious implementation would have left the job
     * held forever while the review said REJECTED, and the user's progress screen would have said
     * "running additional checks" indefinitely. This asserts the job actually moves.
     */
    @Test
    void rejectFailsTheJobAndNeverImports() {
        HeldStatement held = seedHold("HLD-2026-100003");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/HLD-2026-100003/reject", admin,
                "{\"reason\":\"Second section's rows never reached the ledger.\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(heldStatementRepository.findById(held.getId()).orElseThrow().getStatus())
                .isEqualTo(HeldStatement.Status.REJECTED);

        ImportJob job = importJobRepository.findById(held.getImportJobId()).orElseThrow();
        assertThat(job.getStatus())
                .as("the job must leave the hold, or the user waits forever")
                .isEqualTo(ImportJob.Status.FAILED);
        assertThat(job.getFailureCode())
                .as("a bare failure after being told we were checking something is not an answer")
                .isEqualTo(ErrorCode.IMPORT_TRUST_REVIEW_REJECTED.name());
    }

    /**
     * The engineer's investigation notes survive the admin's decision.
     *
     * <p>There is one notes column; a rejection reason written into it would destroy the findings
     * the rejection was based on. The reason belongs in the event history instead, which this
     * checks is where it actually landed.
     */
    @Test
    void theRejectionReasonGoesToTheHistoryNotOverTheNotes() {
        HeldStatement held = seedHold("HLD-2026-100004");
        held.addNotes("Section 2's closing balance does not match the printed summary.");
        heldStatementRepository.save(held);
        User admin = createUser("ADMIN");

        post("/api/v1/admin/held-statements/HLD-2026-100004/reject", admin,
                "{\"reason\":\"Unsupportable layout.\"}");

        assertThat(heldStatementRepository.findById(held.getId()).orElseThrow().getEngineerNotes())
                .contains("closing balance");
        assertThat(eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId()))
                .anyMatch(e -> "REJECTED".equals(e.getEventType())
                        && e.getNotes() != null && e.getNotes().contains("Unsupportable"));
    }

    /** A double-clicked button gets an explainable conflict, not a 500 and not a second import. */
    @Test
    void resolvingTwiceIsAConflict() {
        seedHold("HLD-2026-100005");
        User admin = createUser("ADMIN");

        assertThat(post("/api/v1/admin/held-statements/HLD-2026-100005/approve", admin, "{}")
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> second =
                post("/api/v1/admin/held-statements/HLD-2026-100005/approve", admin, "{}");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("IMPORTED");
    }

    /** Rejecting something already approved must not undo the import. */
    @Test
    void anApprovedHoldCannotThenBeRejected() {
        HeldStatement held = seedHold("HLD-2026-100006");
        User admin = createUser("ADMIN");
        post("/api/v1/admin/held-statements/HLD-2026-100006/approve", admin, "{}");

        ResponseEntity<String> response =
                post("/api/v1/admin/held-statements/HLD-2026-100006/reject", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(importJobRepository.findById(held.getImportJobId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.COMPLETED);
    }

    @Test
    void anUnknownHeldIdIsNotFound() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/held-statements/HLD-2026-999999", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------------------- Plan 3: rerun-parser / findings

    @Test
    void rerunParserRefusesAUserWithoutTheTrustReviewPermission() {
        HeldStatement held = seedHoldWithRealBytes("HLD-2026-390001");
        User ordinary = createUser("USER");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/" + held.getHeldId() + "/rerun-parser", ordinary, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void rerunParserReturnsTheComparison() throws Exception {
        HeldStatement held = seedHoldWithRealBytes("HLD-2026-390002");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/" + held.getHeldId() + "/rerun-parser", admin, "{}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("stillHeld")).isNotNull();
        assertThat(data.has("previousParserVersion")).isTrue();
        assertThat(data.has("currentParserVersion")).isTrue();
    }

    @Test
    void findingsEndpointSavesRootCauseAndFixReference() throws Exception {
        HeldStatement held = seedHoldWithRealBytes("HLD-2026-390003");
        User admin = createUser("ADMIN");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/" + held.getHeldId() + "/findings", admin,
                "{\"rootCause\":\"Header misdetected\",\"fixReference\":\"PR #950\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = mapper.readTree(response.getBody()).get("data");
        assertThat(data.get("rootCause").asText()).isEqualTo("Header misdetected");
        assertThat(data.get("fixReference").asText()).isEqualTo("PR #950");
    }

    @Test
    void findingsRefusesAUserWithoutTheTrustReviewPermission() {
        HeldStatement held = seedHoldWithRealBytes("HLD-2026-390004");
        User ordinary = createUser("USER");

        ResponseEntity<String> response = post(
                "/api/v1/admin/held-statements/" + held.getHeldId() + "/findings", ordinary,
                "{\"rootCause\":\"x\"}");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
