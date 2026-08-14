package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GET /import/sessions ("Continue previous import") lists every one of a user's STAGED, unexpired
 * {@code ImportSession}s -- {@code ImportSessionService.listActiveSessions} has no filter on
 * session kind. But {@code ImportController.toSummary} unconditionally calls
 * {@code readStagedRows}, which {@code requireKind}s SINGLE_ACCOUNT and throws for a
 * MULTI_ACCOUNT session (a composite PDF upload, e.g. HSBC's combined savings+card statement --
 * see {@code ImportSessionService.createMultiSection}). Before the fix, one staged multi-account
 * session was enough to break the WHOLE list for that user, not just its own entry: the stream's
 * {@code .map(this::toSummary)} throws on the first MULTI_ACCOUNT session it meets, so a user with
 * an unrelated single-account session staged at the same time also lost their "Continue previous
 * import" entry.
 *
 * <p>Uses {@code ImportSessionService.createMultiSection} directly to stage the multi-account
 * session, the same way {@code MultiSectionSharedTransferIT} does -- it persists already-parsed
 * sections and doesn't need a real multi-account PDF to run through the parser.
 */
class ImportControllerSessionsIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private ImportSessionService importSessionService;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final byte[] FILE = "irrelevant-for-this-test".getBytes();

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

    private StagedRow stagedRow() {
        return new StagedRow(LocalDate.of(2026, 7, 1), "Coffee Shop", new BigDecimal("150.00"),
                "EXPENSE", "Food & Dining", "rule", null, false, null, null);
    }

    private void stageSingleAccountSession(User user, String fileName) {
        importSessionService.createSession(user.getId(), fileName, FILE, List.of(stagedRow()), null);
    }

    private void stageMultiAccountSession(User user, String fileName) {
        StagedAccountSection section = new StagedAccountSection(
                null, List.of(stagedRow()), 1, 0, List.of());
        importSessionService.createMultiSection(user.getId(), fileName, FILE, List.of(section));
    }

    private ResponseEntity<String> listSessions(User user) {
        return restTemplate.exchange("/api/v1/import/sessions", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    @Test
    void aStagedMultiAccountSession_doesNotBreakTheSessionsList() throws Exception {
        User user = createUser();
        stageSingleAccountSession(user, "single-account-statement.csv");
        stageMultiAccountSession(user, "composite-statement.pdf");

        ResponseEntity<String> response = listSessions(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sessions = mapper.readTree(response.getBody()).get("data");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).get("fileName").asText()).isEqualTo("single-account-statement.csv");
    }

    @Test
    void aUserWithOnlyAStagedMultiAccountSession_getsAnEmptyListNotAnError() {
        User user = createUser();
        stageMultiAccountSession(user, "composite-statement.pdf");

        ResponseEntity<String> response = listSessions(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"data\":[]");
    }
}
