package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.PdfStagingSessionResponse;
import com.finora.entity.User;
import com.finora.imports.ImportService;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The end of the wire for the single-account PDF verification fix: a real upload, a real Postgres,
 * and a real row in {@code import_verification_findings}.
 *
 * <p>Before the fix, this table held <b>zero</b> rows for every single-account PDF ever imported.
 * The rules ran, {@code ImportService.toStagingResponse} dropped the report while converting the
 * section into the response, {@code recordPdfParsed} then forwarded that dropped field, and
 * {@code ImportVerificationRecorder} skips a null report without complaint. Nothing failed and
 * nothing was recorded — "which rules ran on this import, and what did they find" was
 * unanswerable for the most common PDF shape there is.
 *
 * <p>An integration test rather than a mock-verification because the in-memory assertion
 * ({@code VerificationSurvivesStagingConversionTest}) can only prove the recorder was CALLED with
 * the report. The recorder commits in its own transaction and swallows its own failures by design,
 * so "was called" and "is durably stored" are genuinely different claims, and only this one
 * settles the second.
 *
 * <p>The multi-section case is asserted alongside as the contrast that was never broken.
 */
@TestPropertySource(properties = {
        "app.import.queue.enabled=false",
        "app.learning.queue.enabled=false"})
class SingleAccountPdfVerificationIsPersistedIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private ImportVerificationFindingRepository findingRepository;

    private User user() {
        User user = new User();
        user.setEmail("pdf-verification-persisted-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("PDF Verification IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private List<ImportVerificationFinding> findingsFor(PdfStagingSessionResponse staged) {
        var analysis = analysisRepository.findByImportSessionIdOrderByCreatedAtDesc(staged.sessionId());
        assertThat(analysis).as("the upload recorded an analysis row to hang findings off").isNotEmpty();
        return findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(analysis.get(0).getId());
    }

    @Test
    void aSingleAccountPdfImportPersistsTheRulesThatRanOnIt() throws Exception {
        User user = user();
        byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/pdf/separate_debit_credit_balance_sample.pdf"));

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(user.getId(),
                new MockMultipartFile("file", "single_account.pdf", "application/pdf", bytes), null);

        assertThat(staged.multiAccount()).isFalse();
        assertThat(staged.staging().verification()).as("in the response the user is shown").isNotNull();

        var stored = findingsFor(staged);
        assertThat(stored).as("and durably, which was the half that recorded nothing at all").isNotEmpty();
        assertThat(stored).extracting(ImportVerificationFinding::getRule).contains("BALANCE_CHAIN");
        assertThat(stored).allSatisfy(f -> {
            assertThat(f.getSectionIndex()).isEqualTo(0);
            assertThat(f.getOutcome()).isNotBlank();
        });
        // The response and the evidence table agree -- one is not a differently-filtered view of
        // the other.
        assertThat(stored).extracting(ImportVerificationFinding::getRule)
                .containsExactlyInAnyOrderElementsOf(staged.staging().verification().findings().stream()
                        .map(com.finora.dto.ImportDto.VerificationFinding::rule).toList());
    }

    @Test
    void aMultiSectionPdfImportStillPersistsOneSetOfFindingsPerSection() throws Exception {
        User user = user();

        PdfStagingSessionResponse staged = importService.parseAndStagePdfWithSession(user.getId(),
                new MockMultipartFile("file", "composite.pdf", "application/pdf",
                        PdfFixtureBuilder.buildMultiSectionCompositeStatementSample()), null);

        assertThat(staged.multiAccount()).isTrue();
        var stored = findingsFor(staged);
        assertThat(stored).isNotEmpty();
        assertThat(stored).extracting(ImportVerificationFinding::getSectionIndex)
                .as("section-scoped, unchanged by the single-account fix")
                .contains(0, 1);
    }
}
