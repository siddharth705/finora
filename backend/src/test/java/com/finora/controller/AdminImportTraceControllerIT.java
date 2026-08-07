package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.imports.analysis.ParseDiagnostics;
import com.finora.imports.analysis.StatementAnalysisRecorder;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.JwtService;
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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trace endpoint's gate and its boundary.
 *
 * <p>Two things are proved. The endpoint answers to {@code PLATFORM_DIAGNOSTICS_VIEW} and not to
 * "whatever permission the caller happens to hold" — a new admin surface quietly accepting an
 * unrelated permission is the failure mode V34 and V61 both exist to prevent. And the response
 * carries no file name and no user id, matching the boundary its sibling
 * {@code AdminStatementAnalysisController} already holds: a statement's file name routinely carries
 * a customer's name, and this is a platform engineering surface rather than a per-user one.
 */
@TestPropertySource(properties = {
        "app.import.queue.enabled=false",
        "app.learning.queue.enabled=false"})
class AdminImportTraceControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private StatementAnalysisRecorder analysisRecorder;
    @Autowired private JwtService jwtService;
    @Autowired private com.finora.repository.RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser(String role) {
        User user = new User();
        user.setEmail("import-trace-controller-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Trace Controller IT User");
        user.setRole(role);
        user.setAccountScope(User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        // Mints against a real refresh-token row rather than a random session id: since the
        // session-revocation fix, JwtAuthFilter rejects a token whose sid has no live session.
        headers.setBearerAuth(
                com.finora.testsupport.TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** An upload with a distinctive file name, so its absence from the response is provable. */
    private String recordedUpload(User owner) {
        ImportSession session = new ImportSession();
        session.setUserId(owner.getId());
        session.setFileName("jane-doe-hdfc-statement.pdf");
        session.setFileContent("synthetic".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(Instant.now().plusSeconds(3600));
        ImportSession saved = importSessionRepository.save(session);

        return analysisRecorder.recordParsed(owner.getId(),
                StatementAnalysisSession.Source.CUSTOMER_IMPORT, "jane-doe-hdfc-statement.pdf",
                "PDF", 40960L, "FP-TEST-1A9E", 1, 812L,
                ParseDiagnostics.of(124, Map.of()), saved.getId());
    }

    private ResponseEntity<String> get(String path, User caller) {
        return restTemplate.exchange(path, HttpMethod.GET,
                new HttpEntity<>(bearerFor(caller)), String.class);
    }

    @Test
    void anAdministratorCanTraceOneImportInOneRequest() throws Exception {
        User admin = createUser("ADMIN");
        String reference = recordedUpload(createUser("USER"));

        ResponseEntity<String> response =
                get("/api/v1/admin/imports/traces/by-analysis/" + reference, admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode trace = mapper.readTree(response.getBody()).path("data");
        // The criterion is "in a single view": every block the sentence names has to be present in
        // this one response, not reachable from it.
        assertThat(trace.path("analysisReference").asText()).isEqualTo(reference);
        assertThat(trace.has("analysis")).isTrue();
        assertThat(trace.has("verification")).isTrue();
        assertThat(trace.has("learning")).isTrue();
        assertThat(trace.has("completion")).isTrue();
        assertThat(trace.has("stages")).isTrue();
    }

    @Test
    void theResponseCarriesNoFileNameAndNoUserId() {
        User admin = createUser("ADMIN");
        String reference = recordedUpload(createUser("USER"));

        ResponseEntity<String> response =
                get("/api/v1/admin/imports/traces/by-analysis/" + reference, admin);

        assertThat(response.getBody())
                .as("a statement's file name routinely carries a customer's name; the reference is "
                    + "the handle instead")
                .doesNotContain("jane-doe")
                .doesNotContain("userId");
    }

    @Test
    void aConsumerAccountCannotReadATrace() {
        // The gate. Without @PreAuthorize the admin path rule alone would leave this open to every
        // logged-in consumer account -- import diagnostics for the whole platform.
        User consumer = createUser("USER");
        consumer.setAccountScope(User.SCOPE_USER);
        userRepository.save(consumer);
        String reference = recordedUpload(consumer);

        ResponseEntity<String> response =
                get("/api/v1/admin/imports/traces/by-analysis/" + reference, consumer);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();
    }

    @Test
    void anUnknownReferenceIsANotFoundRatherThanAnEmptyTrace() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response =
                get("/api/v1/admin/imports/traces/by-analysis/SA-99999999-9999", admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownJobIsANotFoundToo() {
        User admin = createUser("ADMIN");

        ResponseEntity<String> response =
                get("/api/v1/admin/imports/traces/by-job/" + UUID.randomUUID(), admin);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
