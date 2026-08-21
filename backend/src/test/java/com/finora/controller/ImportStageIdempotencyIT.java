package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.User;
import com.finora.imports.ImportSessionService;
import com.finora.repository.RefreshTokenRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
import com.finora.testsupport.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Duplicate-upload protection for the synchronous stage path (distributed-resilience-patterns-
 * audit-2026-08-14.md §3; V79__import_session_stage_idempotency.sql). Mirrors BH-019's coverage
 * of the equivalent guarantee for the asynchronous job queue, applied to POST /csv/stage's own
 * import_sessions instead of import_jobs.
 *
 * <p>Two layers, tested separately: {@link #sameFileUploadedTwice_returnsTheSameSession_notADuplicate}
 * exercises the app-level pre-check (real HTTP round trips), and
 * {@link #concurrentCreateSessionCallsForTheSameContent_theLoserHitsTheDatabaseConstraint} proves
 * the actual correctness guarantee -- the partial unique index -- independent of that check, the
 * same way V74's own migration test proves its index rather than only its app-level short-circuit.
 */
@TestPropertySource(properties = {
        // Same reasoning ImportJobEndpointIT's own comment already gives for the same property:
        // importStageLimiter is 10 per 10 minutes per IP, shared across /csv/stage, /pdf/stage AND
        // /import/jobs, and this class alone makes more than that from one loopback address across
        // its four tests -- before even accounting for every OTHER IT class sharing the default
        // context's cached rate-limiter instance across a full suite run. Raised here, in this
        // class specifically (not application-test.yml), for the same reason: a limit that can
        // never be tested against the shipped value would make it unverifiable, but lifting it
        // globally would mean no integration context ever runs with the real ceiling.
        "app.rate-limit.import-stage.max=10000"
})
class ImportStageIdempotencyIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ImportSessionService importSessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String READABLE_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            """;

    private User createUser() {
        User user = new User();
        user.setEmail("stage-idempotency-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Stage Idempotency IT Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpEntity<MultiValueMap<String, Object>> uploadRequest(User user, String csv, String fileName) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return fileName; }
        });
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<String> stage(User user, String csv, String fileName) {
        return restTemplate.exchange("/api/v1/import/csv/stage", HttpMethod.POST,
                uploadRequest(user, csv, fileName), String.class);
    }

    private ResponseEntity<String> listSessions(User user) {
        return restTemplate.exchange("/api/v1/import/sessions", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void sameFileUploadedTwice_returnsTheSameSession_notADuplicate() throws Exception {
        User user = createUser();

        ResponseEntity<String> first = stage(user, READABLE_CSV, "statement.csv");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstSessionId = mapper.readTree(first.getBody()).get("data").get("sessionId").asText();

        // A double-click, or a client retrying a request whose response was lost -- the exact
        // same bytes, re-uploaded, before the first session has expired.
        ResponseEntity<String> second = stage(user, READABLE_CSV, "statement.csv");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondSessionId = mapper.readTree(second.getBody()).get("data").get("sessionId").asText();

        assertThat(secondSessionId)
                .as("the second upload should hand back the session already staged, not create a second one")
                .isEqualTo(firstSessionId);

        ResponseEntity<String> sessions = listSessions(user);
        JsonNode data = mapper.readTree(sessions.getBody()).get("data");
        assertThat(data).as("only one staged session should exist for this user").hasSize(1);
    }

    @Test
    void sameFileUploadedByTwoDifferentUsers_isNotTreatedAsADuplicate() throws Exception {
        // The constraint is scoped per (user_id, content_hash) -- two different users happening to
        // upload byte-identical statements (a shared bank template, or the same public sample
        // file) must each get their own session, not collide with each other's.
        User first = createUser();
        User second = createUser();

        ResponseEntity<String> firstResponse = stage(first, READABLE_CSV, "statement.csv");
        ResponseEntity<String> secondResponse = stage(second, READABLE_CSV, "statement.csv");

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstSessionId = mapper.readTree(firstResponse.getBody()).get("data").get("sessionId").asText();
        String secondSessionId = mapper.readTree(secondResponse.getBody()).get("data").get("sessionId").asText();
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);
    }

    @Test
    void differentFileContent_isNeverTreatedAsADuplicate() throws Exception {
        User user = createUser();
        String otherCsv = """
                Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                02/07/2026,UPI-DIFFERENT MERCHANT-0000000002,50.00,,24830.00
                """;

        ResponseEntity<String> first = stage(user, READABLE_CSV, "statement.csv");
        ResponseEntity<String> second = stage(user, otherCsv, "statement-2.csv");

        String firstSessionId = mapper.readTree(first.getBody()).get("data").get("sessionId").asText();
        String secondSessionId = mapper.readTree(second.getBody()).get("data").get("sessionId").asText();
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);

        ResponseEntity<String> sessions = listSessions(user);
        assertThat(mapper.readTree(sessions.getBody()).get("data")).hasSize(2);
    }

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private DetectedAccountInfo sampleDetected() {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    /**
     * The actual correctness guarantee, independent of the app-level pre-check that the HTTP tests
     * above exercise. {@code findLiveSessionByContentHash} is a read followed by a possible write,
     * so two genuinely simultaneous uploads can both see no match and both proceed -- this is what
     * decides between them then. Calling {@code createSession} directly, twice, with the same
     * (user, content hash) simulates exactly that race without needing real concurrent threads:
     * the app-level check is bypassed entirely, so this is purely a test of
     * {@code idx_import_sessions_live_content} (V79).
     */
    @Test
    void concurrentCreateSessionCallsForTheSameContent_theLoserHitsTheDatabaseConstraint() {
        User user = createUser();
        byte[] sameBytes = "identical statement bytes".getBytes(StandardCharsets.UTF_8);

        importSessionService.createSession(user.getId(), "statement.csv", sameBytes,
                List.of(stagedRow()), sampleDetected());

        assertThatThrownBy(() -> importSessionService.createSession(user.getId(), "statement.csv", sameBytes,
                List.of(stagedRow()), sampleDetected()))
                .as("idx_import_sessions_live_content must reject a second live STAGED session for "
                        + "the same (user, content hash), the same way idx_import_jobs_live_content "
                        + "(V74) already does for import_jobs")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
