package com.finora.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.MultiAccountConfirmRequest;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.User;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security fix: {@code ConfirmRequest}, {@code SectionConfirm}, {@code MultiAccountConfirmRequest}
 * and {@code NewAccountRequest} carried zero Bean Validation, and none of the three controller
 * methods that bind them applied {@code @Valid} -- see the "Security fix" doc comments on those
 * records in {@code ImportDto} for the exact NPE/DataIntegrityViolationException each gap produced.
 *
 * <p>These requests never need a real staged session to exist: Bean Validation runs during Spring
 * MVC argument binding, before the controller method body -- and therefore before
 * {@code ImportService}/{@code StatementImportService} ever see the request -- so an arbitrary
 * {@code sessionId}/statement id is enough to prove the 400 comes from validation, not from a
 * downstream lookup failing for an unrelated reason.
 */
class ImportConfirmValidationIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    private final ObjectMapper mapper = new ObjectMapper();

    private User createUser() {
        User user = new User();
        user.setEmail("import-confirm-validation-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Confirm Validation IT Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private HttpHeaders bearerFor(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> post(String path, User user, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, bearerFor(user)), String.class);
    }

    private void assertValidationError(ResponseEntity<String> response, String expectedFieldMention) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.get("errorCode").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("message").asText()).contains(expectedFieldMention);
    }

    private ConfirmedRow row(String referenceNumber) {
        return new ConfirmedRow(LocalDate.now(), "Coffee", BigDecimal.TEN, "EXPENSE",
                "Food", true, "file", null, false, referenceNumber, null);
    }

    // -- /api/v1/import/csv/confirm --------------------------------------------------------------

    @Test
    void csvConfirm_nullRows_isRejectedAsValidationErrorNotA500() throws Exception {
        User user = createUser();
        ConfirmRequest request = new ConfirmRequest(UUID.randomUUID(), null, null, null, null, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertValidationError(response, "rows");
    }

    @Test
    void csvConfirm_oversizedReferenceNumber_isRejectedAsValidationError() throws Exception {
        User user = createUser();
        String tooLong = "R".repeat(65); // transactions.reference_number is VARCHAR(64)
        ConfirmRequest request = new ConfirmRequest(UUID.randomUUID(), List.of(row(tooLong)),
                UUID.randomUUID(), null, null, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertValidationError(response, "referenceNumber");
    }

    @Test
    void csvConfirm_blankNewAccountName_isRejectedAsValidationErrorNotAConflict() throws Exception {
        User user = createUser();
        NewAccountRequest blankName = new NewAccountRequest(
                "  ", "SAVINGS", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(UUID.randomUUID(), List.of(row(null)),
                null, blankName, null, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        // Before this fix, a blank/oversized NewAccountRequest field reached accounts.name's
        // NOT NULL VARCHAR(120) constraint and came back as a misleading 409 "conflicts with a
        // record" from DataIntegrityViolationException -- not a clean 400.
        assertValidationError(response, "name");
    }

    @Test
    void csvConfirm_oversizedNewAccountIfscCode_isRejectedAsValidationError() throws Exception {
        User user = createUser();
        NewAccountRequest oversizedIfsc = new NewAccountRequest(
                "My New Account", "SAVINGS", null, null, null, null, null, null, null,
                "TOOLONGTOFITINELEVENCHARS",
                null, null, null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(UUID.randomUUID(), List.of(row(null)),
                null, oversizedIfsc, null, null, null);

        ResponseEntity<String> response = post("/api/v1/import/csv/confirm", user, request);

        assertValidationError(response, "ifscCode");
    }

    // -- /api/v1/import/pdf/confirm-multi ---------------------------------------------------------

    @Test
    void pdfConfirmMulti_nullSections_isRejectedAsValidationErrorNotA500() throws Exception {
        User user = createUser();
        MultiAccountConfirmRequest request = new MultiAccountConfirmRequest(UUID.randomUUID(), null);

        ResponseEntity<String> response = post("/api/v1/import/pdf/confirm-multi", user, request);

        assertValidationError(response, "sections");
    }

    // -- /api/v1/statement-imports/{id}/reimport/confirm -------------------------------------------

    @Test
    void reimportConfirm_nullRows_isRejectedAsValidationErrorNotA500() throws Exception {
        User user = createUser();
        // sessionId is null here on purpose -- reimport legitimately never sends one (see
        // ConfirmRequest's own "Security fix" doc comment) -- and a fabricated statement id is
        // enough, since validation runs before the controller method (and its ownership lookup)
        // is ever invoked.
        ConfirmRequest request = new ConfirmRequest(null, null, UUID.randomUUID(), null, null, null, null);

        ResponseEntity<String> response = post(
                "/api/v1/statement-imports/" + UUID.randomUUID() + "/reimport/confirm", user, request);

        assertValidationError(response, "rows");
    }
}
