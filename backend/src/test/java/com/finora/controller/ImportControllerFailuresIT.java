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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Premium Import Reliability v1, §2.1 -- the durable failure record's read side.
 * {@code ImportService.recordParseFailure} already writes a {@code StatementAnalysisSession} on
 * every sync-path failure; {@code GET /import/failures} is the first thing that reads it back for
 * the customer who owns it. The ownership boundary is the point of this file -- a user's own
 * failures and nothing else, ever, including another user's failures and an admin's own diagnostic
 * probing through the separate analysis workbench.
 */
class ImportControllerFailuresIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    /** No header row and no columns at all -- the real staging pipeline rejects this with a real
     *  ErrorCode (IMPORT_NO_HEADER_DETECTED), the same fixture shape AdminAnalysisServiceIT uses
     *  for its own "the engine cannot read this" case. Wholly invented, not from any real file. */
    private static final String UNREADABLE_CSV = "this file has no header row and no columns at all";

    private static final String READABLE_CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            """;

    private User createUser() {
        User user = new User();
        user.setEmail("import-failures-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Failures IT Test User");
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

    private ResponseEntity<String> listFailures(User user) {
        return restTemplate.exchange("/api/v1/import/failures", HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);
    }

    private void stageAndExpectFailure(User user, String fileName) {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/csv/stage", HttpMethod.POST, uploadRequest(user, UNREADABLE_CSV, fileName), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void aUsersOwnFailedImport_isReturnedByReferenceFileNameAndCode() throws Exception {
        User user = createUser();
        stageAndExpectFailure(user, "unreadable-statement.csv");

        ResponseEntity<String> response = listFailures(user);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode failures = mapper.readTree(response.getBody()).get("data");
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).get("fileName").asText()).isEqualTo("unreadable-statement.csv");
        assertThat(failures.get(0).get("failureCode").asText()).isNotBlank();
        assertThat(failures.get(0).get("reference").asText()).startsWith("SA-");
        assertThat(failures.get(0).get("createdAt").asText()).isNotBlank();
    }

    @Test
    void anotherUsersFailedImports_areNeverReturned() {
        User uploader = createUser();
        User bystander = createUser();
        stageAndExpectFailure(uploader, "someone-elses-statement.csv");

        ResponseEntity<String> response = listFailures(bystander);

        assertThat(response.getBody()).doesNotContain("someone-elses-statement.csv");
    }

    @Test
    void aSuccessfullyStagedImport_isNotListedAsAFailure() {
        User user = createUser();
        ResponseEntity<String> stageResponse = restTemplate.exchange(
                "/api/v1/import/csv/stage", HttpMethod.POST,
                uploadRequest(user, READABLE_CSV, "readable-statement.csv"), String.class);
        assertThat(stageResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> response = listFailures(user);

        assertThat(response.getBody()).doesNotContain("readable-statement.csv");
    }

    @Test
    void theResponseNeverIncludesFailureDetail() throws Exception {
        // failureDetail can carry a fragment of the document that defeated the parser
        // (StatementAnalysisRecorder's own doc comment) -- admin/debug-only, never customer-facing.
        User user = createUser();
        stageAndExpectFailure(user, "checking-for-leakage.csv");

        ResponseEntity<String> response = listFailures(user);

        assertThat(response.getBody()).doesNotContain("failureDetail");
        JsonNode failure = mapper.readTree(response.getBody()).get("data").get(0);
        assertThat(failure.fieldNames()).toIterable()
                .as("only the four fields this DTO declares -- nothing else leaked through")
                .containsExactlyInAnyOrder("reference", "fileName", "failureCode", "createdAt");
    }

    @Test
    void unauthenticatedRequest_isRejected() {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/failures", HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
