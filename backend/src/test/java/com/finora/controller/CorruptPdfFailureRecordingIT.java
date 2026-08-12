package com.finora.controller;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.imports.analysis.StatementAnalysisSession;
import com.finora.imports.analysis.StatementAnalysisSessionRepository;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
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

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the loop the ErrorCode addition exists for: a corrupted PDF uploaded through the real
 * sync path must now record {@code failureCode = "IMPORT_011"} on its
 * {@link StatementAnalysisSession} row, not null. Before this fix that field was null for every
 * corrupt-PDF failure -- indistinguishable from any other codeless failure in the failure_code
 * histogram, the customer-facing failures list (Premium Import Reliability v1, §2.1), and any
 * future retry classification. Unit coverage for the exception itself lives in
 * {@code CorruptPdfErrorCodeTest}; this file proves the code survives the whole path from upload
 * to the persisted row.
 */
class CorruptPdfFailureRecordingIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenRepository refreshTokens;
    @Autowired private StatementAnalysisSessionRepository analysisSessionRepository;

    private User createUser() {
        User user = new User();
        user.setEmail("corrupt-pdf-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Corrupt PDF IT Test User");
        user.setRole("USER");
        user.setAccountScope(User.SCOPE_USER);
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Same fabrication technique as CorruptPdfErrorCodeTest: a wholly synthetic
     *  PdfFixtureBuilder sample, truncated partway through its object stream. */
    private static byte[] corruptedPdf() throws Exception {
        byte[] valid = PdfFixtureBuilder.buildSummaryWithOneTransactionalSectionSample();
        return Arrays.copyOf(valid, valid.length * 2 / 3);
    }

    @Test
    void aCorruptedPdfUpload_isRecordedWithTheCorruptPdfFailureCode() throws Exception {
        User user = createUser();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestSessions.accessTokenFor(jwtService, refreshTokens, user));
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(corruptedPdf()) {
            @Override public String getFilename() { return "damaged-statement.pdf"; }
        });

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/import/pdf/stage", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("IMPORT_011");

        var recorded = analysisSessionRepository
                .findByUserIdAndSourceAndOutcomeOrderByCreatedAtDesc(user.getId(),
                        StatementAnalysisSession.Source.CUSTOMER_IMPORT, StatementAnalysisSession.Outcome.FAILED,
                        org.springframework.data.domain.PageRequest.of(0, 1));

        assertThat(recorded).hasSize(1);
        // ImportService.recordParseFailure records ApiException.getCode().name() -- the Java enum
        // identifier, not the wire code -- for every ErrorCode, not something special-cased here.
        assertThat(recorded.get(0).getFailureCode())
                .as("failureCode must no longer be null for a corrupt PDF")
                .isEqualTo(com.finora.exception.ErrorCode.IMPORT_CORRUPT_PDF.name());
        assertThat(recorded.get(0).getFileName()).isEqualTo("damaged-statement.pdf");
    }
}
