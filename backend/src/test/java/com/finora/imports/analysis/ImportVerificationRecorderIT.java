package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto;
import com.finora.entity.User;
import com.finora.imports.jobs.ImportJobStore;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verification outcomes surviving the request that produced them.
 *
 * <p>Before this, the four rules ran on every staged statement, their findings reached the staging
 * response, and that was the end of them — "which rules ran on last week's import, and what did they
 * find" had no answer, and {@code layout -> verification rate} could not be computed at all.
 *
 * <p>Against a real database rather than a mocked repository because two of the properties here are
 * the database's: the CHECK constraint that says a finding has exactly one owner, and the fact that
 * what comes back out of a TEXT column is what went in. A mock would agree with whatever the code
 * believed.
 */
@TestPropertySource(properties = {
        "app.import.queue.enabled=false",
        "app.learning.queue.enabled=false"})
class ImportVerificationRecorderIT extends AbstractIntegrationTest {

    @Autowired private ImportVerificationRecorder recorder;
    @Autowired private ImportVerificationFindingRepository findingRepository;
    @Autowired private StatementAnalysisRecorder analysisRecorder;
    @Autowired private StatementAnalysisSessionRepository analysisRepository;
    @Autowired private ImportJobStore jobStore;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("verification-recorder-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Verification Recorder IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private String analysis(UUID userId) {
        return analysisRecorder.recordParsed(userId, StatementAnalysisSession.Source.CUSTOMER_IMPORT,
                "statement.pdf", "PDF", 4096L, "FP-TEST-1A9E", 1, 250L,
                ParseDiagnostics.of(124, Map.of()), UUID.randomUUID());
    }

    /** The real shape of a BALANCE_CHAIN failure: counts alongside per-row balances. */
    private static ImportDto.VerificationReport balanceChainFailure() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowsChecked", 124);
        details.put("rowsWithBalance", 124);
        details.put("anchoredOnOpeningBalance", true);
        details.put("discrepancies", List.of(Map.of(
                "rowIndex", 17,
                "expectedBalance", new BigDecimal("48221.50"),
                "actualBalance", new BigDecimal("47785.50"),
                "difference", new BigDecimal("436.00"))));
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("BALANCE_CHAIN", "FAILED", details),
                new ImportDto.VerificationFinding("SUMMARY_TOTALS", "NOT_APPLICABLE",
                        Map.of("reason", "No printed totals were available for this section, so there "
                                + "was nothing to compare against."))));
    }

    @Test
    void everyRuleThatRanIsStillAnswerableAfterTheRequestEnds() {
        User user = user();
        String reference = analysis(user.getId());

        int written = recorder.recordForAnalysis(reference, List.of(balanceChainFailure()));

        assertThat(written).isEqualTo(2);
        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        var stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId);
        assertThat(stored).extracting(ImportVerificationFinding::getRule)
                .containsExactly("BALANCE_CHAIN", "SUMMARY_TOTALS");
        assertThat(stored).extracting(ImportVerificationFinding::getOutcome)
                .as("NOT_APPLICABLE is recorded, not dropped -- 'checked, nothing to check with' "
                    + "and 'never checked' are different claims and only one is true")
                .containsExactly("FAILED", "NOT_APPLICABLE");
    }

    @Test
    void whatComesBackOutOfTheColumnCarriesNoBalances() {
        // The allowlist is unit-tested in ImportVerificationDetailAllowlistTest; this asserts it
        // still holds after a round trip through Jackson and a TEXT column, which is where a
        // serialiser that helpfully re-added a field would show up.
        User user = user();
        String reference = analysis(user.getId());

        recorder.recordForAnalysis(reference, List.of(balanceChainFailure()));

        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        String stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId)
                .get(0).getDetailsJson();

        assertThat(stored)
                .as("the count is the diagnostic; the balances are the customer's statement")
                .contains("discrepanciesCount").contains("rowsChecked")
                .doesNotContain("48221.50").doesNotContain("expectedBalance");
    }

    @Test
    void eachSectionOfACompositeStatementKeepsItsOwnFindings() {
        // A composite statement's sections have separate balance chains and one can verify while
        // another does not. Collapsing them would lose exactly the distinction the framework
        // computes per section.
        User user = user();
        String reference = analysis(user.getId());

        recorder.recordForAnalysis(reference, List.of(
                new ImportDto.VerificationReport(List.of(
                        new ImportDto.VerificationFinding("BALANCE_CHAIN", "VERIFIED",
                                Map.of("rowsChecked", 40)))),
                new ImportDto.VerificationReport(List.of(
                        new ImportDto.VerificationFinding("BALANCE_CHAIN", "FAILED",
                                Map.of("rowsChecked", 12))))));

        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        var stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId);
        assertThat(stored).extracting(f -> f.getSectionIndex() + ":" + f.getOutcome())
                .containsExactly("0:VERIFIED", "1:FAILED");
    }

    @Test
    void theAsynchronousPathRecordsAgainstItsJobBecauseItHasNoAnalysisRow() {
        User user = user();
        var job = jobStore.enqueue(user.getId(), "statement.csv",
                "hash-" + UUID.randomUUID(), "objects/x");

        int written = recorder.recordForJob(job.getId(), List.of(balanceChainFailure()));

        assertThat(written).isEqualTo(2);
        var stored = findingRepository.findByImportJobIdOrderBySectionIndexAscRuleAsc(job.getId());
        assertThat(stored).hasSize(2);
        assertThat(stored).allSatisfy(finding -> assertThat(finding.getAnalysisSessionId())
                .as("exactly one owner, enforced by the CHECK constraint rather than by convention")
                .isNull());
    }

    @Test
    void anUnknownAnalysisReferenceIsANoOpRatherThanAThrow() {
        // recordParsed returns null when the evidence row could not be written, and the import must
        // still succeed. A finding with no owner is not worth inventing one for, and refusing loudly
        // here would fail the upload over a telemetry gap that is already logged.
        assertThatCode(() -> {
            assertThat(recorder.recordForAnalysis(null, List.of(balanceChainFailure()))).isZero();
            assertThat(recorder.recordForAnalysis("SA-99999999-9999", List.of(balanceChainFailure())))
                    .isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void aStagingPathThatVerifiedNothingWritesNothing() {
        // StagingResponse.verification is nullable and means "not checked", which is distinct from a
        // report saying NOT_APPLICABLE. Recording a row for it would erase that distinction.
        User user = user();
        String reference = analysis(user.getId());

        int written = recorder.recordForAnalysis(reference, java.util.Collections.singletonList(null));

        assertThat(written).isZero();
        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        assertThat(findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId))
                .isEmpty();
    }
}
