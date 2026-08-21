package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.DetectedAccountInfo;
import com.finora.dto.ImportDto.StagedAccountSection;
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
import org.springframework.http.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /import/sessions -- "your unfinished imports" (ADR-0002). Reproduces and pins the bug this
 * class exists to prevent regressing: {@code ImportController.toSummary} calls
 * {@code ImportSessionService.readStagedRows}, which only a SINGLE_ACCOUNT session supports. Before
 * {@code ImportSessionService.listResumableSessions} existed, this endpoint fed EVERY active
 * session (both kinds) straight into {@code toSummary}, so a user with even one staged
 * MULTI_ACCOUNT PDF session (see {@code ImportController.stagePdf}'s own doc comment) got an
 * exception for their entire response -- not just that one session.
 *
 * <p>Sessions are created directly via {@link ImportSessionService#createSession}/
 * {@link ImportSessionService#createMultiSection} rather than through the real stage endpoints --
 * same shortcut {@code ExpiredSessionCleanupBoundaryIT} takes -- since what this class needs is a
 * session of a given kind sitting in the STAGED state, not PDF parsing itself.
 */
class ImportControllerSessionsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ImportSessionService importSessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("import-sessions-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Sessions IT Test User");
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

    private ResponseEntity<String> listSessions(User user) {
        return restTemplate.exchange("/api/v1/import/sessions", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    private StagedRow sampleRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private DetectedAccountInfo sampleDetected() {
        return new DetectedAccountInfo("Test Bank", "SAVINGS", new BigDecimal("1000"), new BigDecimal("900"),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, null, null, null, null, null, null, null,
                "SAVINGS", 0.85, false, List.of(), null,
                null, null, null, null, null, null, null);
    }

    private void stageSingleAccountSession(User user, String fileName) {
        importSessionService.createSession(user.getId(), fileName,
                "Date,Description,Amount\n2026-07-01,COFFEE,150.00\n".getBytes(StandardCharsets.UTF_8),
                List.of(sampleRow()), sampleDetected());
    }

    private void stageMultiAccountSession(User user, String fileName) {
        var section = new StagedAccountSection(sampleDetected(), List.of(sampleRow()), 1, 0, List.of());
        importSessionService.createMultiSection(user.getId(), fileName,
                "composite pdf bytes".getBytes(StandardCharsets.UTF_8), List.of(section));
    }

    @Test
    void aStagedSingleAccountSession_isReturned() throws Exception {
        User user = createUser();
        stageSingleAccountSession(user, "single-account.csv");

        ResponseEntity<String> response = listSessions(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sessions = mapper.readTree(response.getBody()).get("data");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("fileName").asText()).isEqualTo("single-account.csv");
        assertThat(sessions.get(0).get("rowCount").asInt()).isEqualTo(1);
    }

    /** The regression test. Before {@code listResumableSessions}, this request threw and the user
     *  saw none of their resumable imports -- not just a list missing the multi-account one. */
    @Test
    void aStagedMultiAccountSession_doesNotBreakTheList_andIsExcludedFromIt() throws Exception {
        User user = createUser();
        stageMultiAccountSession(user, "composite-statement.pdf");

        ResponseEntity<String> response = listSessions(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sessions = mapper.readTree(response.getBody()).get("data");
        assertThat(sessions).isEmpty();
    }

    @Test
    void aUserWithBothKinds_seesOnlyTheSingleAccountSessionInTheList() throws Exception {
        User user = createUser();
        stageSingleAccountSession(user, "resumable.csv");
        stageMultiAccountSession(user, "not-yet-resumable.pdf");

        ResponseEntity<String> response = listSessions(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sessions = mapper.readTree(response.getBody()).get("data");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("fileName").asText()).isEqualTo("resumable.csv");
    }

    @Test
    void anotherUsersSessions_areNeverReturned() throws Exception {
        User owner = createUser();
        User bystander = createUser();
        stageSingleAccountSession(owner, "someone-elses-statement.csv");

        ResponseEntity<String> response = listSessions(bystander);

        assertThat(response.getBody()).doesNotContain("someone-elses-statement.csv");
    }

    @Test
    void unauthenticatedRequest_isRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/sessions", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
