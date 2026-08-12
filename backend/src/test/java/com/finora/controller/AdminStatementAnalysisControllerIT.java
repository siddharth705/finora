package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
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
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Admin's "run the real import engine on a document and keep only the evidence" tool
 * (AdminStatementAnalysisController). The read side (recent/summary/byReference) sits behind
 * PLATFORM_DIAGNOSTICS_VIEW; the write side (analyze) carries its own ENGINE_ANALYSIS_RUN so
 * viewing reports and running the engine are separately grantable -- both are proven here,
 * independently, rather than assuming the class-level rule covers everything.
 */
class AdminStatementAnalysisControllerIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Wholly invented merchants and reference numbers -- see check-fixture-hygiene.sh. */
    private static final String CSV = """
            Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
            01/07/2026,UPI-ZORBIC TEAHOUSE-0000000001,120.00,,24880.00
            02/07/2026,UPI-QUILLWORTH STATIONERS-0000000002,340.50,,24539.50
            """;

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-stmt-analysis-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Admin Statement Analysis IT Test User");
        user.setRole(role);
        // An admin is an ADMIN-PORTAL account. Since V52 the scope is what decides whether a
        // role's permissions are granted at all (AuthorizationService), so a fixture setting
        // only the role builds a state the application refuses to create -- RoleService
        // .requireScopeCanHold rejects attaching a permission-bearing role to a USER-scope row.
        user.setAccountScope("USER".equals(role) ? User.SCOPE_USER : User.SCOPE_ADMIN);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        return headers;
    }

    private HttpEntity<MultiValueMap<String, Object>> uploadRequest(User user) {
        HttpHeaders headers = bearerFor(user);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(CSV.getBytes(StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return "synthetic-statement.csv"; }
        });
        return new HttpEntity<>(body, headers);
    }

    @Test
    void plainUser_isForbiddenFromRecentAnalyses() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromTheSummary() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/summary", HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void plainUser_isForbiddenFromAnalyzingADocument() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses", HttpMethod.POST, uploadRequest(user), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canSeeRecentAnalyses() {
        // ADMIN holds PLATFORM_DIAGNOSTICS_VIEW per V34__platform_diagnostics_permission.sql's
        // seed grant.
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void admin_canSeeTheSummary() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/summary", HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void admin_canAnalyzeADocumentAndReadItBackByReference() throws Exception {
        // ADMIN holds ENGINE_ANALYSIS_RUN per V61__engine_analysis_permission.sql's seed grant.
        User admin = createUser("ADMIN");

        ResponseEntity<String> analyzeResponse = restTemplate.exchange(
                "/api/v1/admin/imports/analyses", HttpMethod.POST, uploadRequest(admin), String.class);

        assertThat(analyzeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode detail = mapper.readTree(analyzeResponse.getBody()).get("data");
        String reference = detail.get("analysis").get("reference").asText();
        assertThat(reference).isNotBlank();

        ResponseEntity<String> byReferenceResponse = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/" + reference,
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(byReferenceResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byReferenceResponse.getBody()).contains(reference);
    }

    @Test
    void plainUser_isForbiddenFromReadingAnAnalysisByReference() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/SA-does-not-exist",
                HttpMethod.GET, new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_readingANonexistentAnalysisReference_returnsNotFound() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/SA-does-not-exist",
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------------------------------------------------------- failure analytics (§4)

    private URI failureSummaryUri(Instant since) {
        return UriComponentsBuilder.fromPath("/api/v1/admin/imports/analyses/failures/summary")
                .queryParam("since", since.toString())
                .build(true).toUri();
    }

    private URI failuresByUserUri(String email) {
        return UriComponentsBuilder.fromPath("/api/v1/admin/imports/analyses/failures/by-user")
                .queryParam("email", email)
                .build(true).toUri();
    }

    @Test
    void plainUser_isForbiddenFromTheFailureSummary() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                failureSummaryUri(Instant.now().minusSeconds(60)), HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_canSeeTheFailureSummary() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                failureSummaryUri(Instant.now().minusSeconds(60)), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** {@code since} has no default -- omitting it entirely is a client error, not an unbounded
     *  scan the endpoint silently absorbs. */
    @Test
    void admin_omittingSinceOnTheFailureSummary_isRejected() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/admin/imports/analyses/failures/summary", HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void plainUser_isForbiddenFromTheFailuresByUserLookup() {
        User user = createUser("USER");
        ResponseEntity<String> response = restTemplate.exchange(
                failuresByUserUri("someone@example.com"), HttpMethod.GET,
                new HttpEntity<>(bearerFor(user)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void admin_lookingUpANonexistentEmail_returnsNotFound() {
        User admin = createUser("ADMIN");
        ResponseEntity<String> response = restTemplate.exchange(
                failuresByUserUri("no-such-customer-" + UUID.randomUUID() + "@example.com"),
                HttpMethod.GET, new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void admin_canLookUpACustomersOwnFailuresByEmail() {
        User admin = createUser("ADMIN");
        User customer = createUser("USER");
        // reference is VARCHAR(24) -- "SA-" plus 20 hex chars from a UUID (dashes stripped) stays
        // comfortably under that while remaining unique per call.
        String reference = "SA-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        // The stored value is the ErrorCode enum NAME, not the wire code -- recentCustomerFailures
        // translates name -> wire code via StatementAnalysisRecorder.wireCodeOf. Storing the wire
        // code here directly would reproduce the exact enum-name/wire-code bug this initiative
        // already fixed once (c44f417): the lookup wouldn't throw, it would just silently return
        // null and this assertion would fail confusingly.
        var failedSession = StatementAnalysisSession.failed(reference,
                customer.getId(), StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "statement.pdf", "PDF", 1L, "FP-SUPPORT",
                com.finora.exception.ErrorCode.IMPORT_CORRUPT_PDF.name(),
                "this must never reach the response", 1L, 0, null);
        analysisRepository.save(failedSession);

        ResponseEntity<String> response = restTemplate.exchange(
                failuresByUserUri(customer.getEmail()), HttpMethod.GET,
                new HttpEntity<>(bearerFor(admin)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains(com.finora.exception.ErrorCode.IMPORT_CORRUPT_PDF.code())
                .as("failureDetail must never reach an admin/support response through this endpoint "
                        + "either -- it reuses the same customer-facing DTO and boundary")
                .doesNotContain("this must never reach the response");
    }
}
