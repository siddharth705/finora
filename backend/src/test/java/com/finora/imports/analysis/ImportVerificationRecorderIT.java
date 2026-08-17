package com.finora.imports.analysis;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto;
import com.finora.entity.User;
import com.finora.imports.StatementUpload;
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

    /** The real shape a ROW_ACCOUNTING WARNING carries -- see {@code RowAccountingValidator}. */
    private static ImportDto.VerificationReport rowAccountingWarning() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("stagedTransactionCount", 124);
        details.put("locatedRowCount", 129);
        details.put("unparseableRowCount", 0);
        details.put("droppedTransactionCandidateCount", 5);
        details.put("droppedTransactionCandidateReasons", Map.of("BUCKET_EMPTY", 5L));
        details.put("explanation", "5 rows outside the recognized transaction table had the shape "
                + "of a transaction candidate and were discarded.");
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("ROW_ACCOUNTING", "WARNING", details)));
    }

    @Test
    void rowAccountingSReasonHistogramSurvivesTheRoundTripThroughTheDatabase() {
        // This is the first evidence type a future observability pass (per-bank unknown rates,
        // layout-drift alerts) will need to read back out of this table -- if the reason histogram
        // doesn't survive the round trip, that future work has nothing to build on. The allowlist
        // itself is unit-tested in ImportVerificationDetailAllowlistTest; this proves it still
        // holds through Jackson and a real TEXT column, same as the balance-chain test above.
        User user = user();
        String reference = analysis(user.getId());

        int written = recorder.recordForAnalysis(reference, List.of(rowAccountingWarning()));

        assertThat(written).isEqualTo(1);
        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        var stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId);
        assertThat(stored).extracting(ImportVerificationFinding::getRule).containsExactly("ROW_ACCOUNTING");
        assertThat(stored).extracting(ImportVerificationFinding::getOutcome).containsExactly("WARNING");

        String detailsJson = stored.get(0).getDetailsJson();
        assertThat(detailsJson)
                .as("counts and the reason histogram are the whole point of this evidence type")
                .contains("droppedTransactionCandidateCount").contains("5")
                .contains("droppedTransactionCandidateReasons").contains("BUCKET_EMPTY")
                .contains("stagedTransactionCount").contains("locatedRowCount")
                .as("our own authored prose isn't on the allowlist yet -- a pre-existing gap shared "
                        + "with every other validator's own explanation field, not fixed here")
                .doesNotContain("discarded");
    }

    /** The real shape a CREDIT_CARD_STATEMENT_TOTALS WARNING carries -- see
     *  {@code CreditCardStatementTotalsValidator}. Every field but {@code explanation} is money read
     *  off the statement's own billing-summary panel, which is exactly why none of it is expected
     *  to survive persistence -- see the test below. */
    private static ImportDto.VerificationReport creditCardStatementTotalsWarning() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousBalance", new BigDecimal("10000.00"));
        details.put("purchases", new BigDecimal("5000.00"));
        details.put("cashAdvances", BigDecimal.ZERO);
        details.put("fees", new BigDecimal("100.00"));
        details.put("paymentsAndCredits", new BigDecimal("2000.00"));
        details.put("totalAmountDue", new BigDecimal("13500.00"));
        details.put("expectedTotalAmountDue", new BigDecimal("13100.00"));
        details.put("difference", new BigDecimal("400.00"));
        details.put("explanation", "The previous balance, purchases, cash advances, fees, and "
                + "payments/credits this statement prints about itself do not add up to its own "
                + "printed total amount due.");
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("CREDIT_CARD_STATEMENT_TOTALS", "WARNING", details)));
    }

    @Test
    void creditCardStatementTotalsCarriesOnlyItsOutcomeThroughPersistence_neverTheMoneyItReconciles() {
        // Unlike ROW_ACCOUNTING's counts, every detail field this validator produces is money read
        // straight off the statement -- the same category StatementTotalsValidator's own
        // openingBalance/closingBalance/totalCredits/totalDebits already stay off the allowlist for
        // (see whatComesBackOutOfTheColumnCarriesNoBalances above). This confirms that stripping is
        // deliberate and consistent for this validator too, not an oversight to "fix" later by
        // widening the allowlist to include a real account balance.
        User user = user();
        String reference = analysis(user.getId());

        int written = recorder.recordForAnalysis(reference, List.of(creditCardStatementTotalsWarning()));

        assertThat(written).isEqualTo(1);
        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        var stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId);
        assertThat(stored).extracting(ImportVerificationFinding::getRule)
                .containsExactly("CREDIT_CARD_STATEMENT_TOTALS");
        assertThat(stored).extracting(ImportVerificationFinding::getOutcome).containsExactly("WARNING");

        // Every field this validator produces is money, so nothing on the allowlist matches and
        // writeDetails returns null outright -- see its own doc comment ("null when nothing
        // structural survived"). Not merely "no money leaked": literally no details column at all.
        assertThat(stored.get(0).getDetailsJson())
                .as("the outcome column already carries the one fact worth persisting for this rule")
                .isNull();
    }

    /** The real shape a CREDIT_CARD_FLOW_RECONCILIATION WARNING carries -- see
     *  {@code CreditCardFlowReconciliationValidator}. {@code evidenceLevel} is a bounded enum
     *  constant, the same category {@code extractionMethod} is for CREDIT_CARD_STATEMENT_TOTALS --
     *  deliberately not on the allowlist yet either, so it is expected to be stripped here too,
     *  alongside the four money fields and the explanation prose. */
    private static ImportDto.VerificationReport creditCardFlowReconciliationWarning() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("evidenceLevel", "FULL_SUMMARY_RECONCILIATION");
        details.put("expectedExpenseAmount", new BigDecimal("5000.00"));
        details.put("observedExpenseAmount", new BigDecimal("3000.00"));
        details.put("differenceExpenseAmount", new BigDecimal("-2000.00"));
        details.put("expectedIncomeAmount", new BigDecimal("4000.00"));
        details.put("observedIncomeAmount", new BigDecimal("4000.00"));
        details.put("differenceIncomeAmount", BigDecimal.ZERO);
        details.put("explanation", "The extracted transactions, summed by direction, do not match "
                + "this statement's own printed purchases and/or payments/credits totals. This does "
                + "not identify which side is wrong -- only that they disagree.");
        return new ImportDto.VerificationReport(List.of(
                new ImportDto.VerificationFinding("CREDIT_CARD_FLOW_RECONCILIATION", "WARNING", details)));
    }

    @Test
    void creditCardFlowReconciliationCarriesOnlyItsOutcomeThroughPersistence_neverTheAmountsItCompares() {
        // Same posture as CREDIT_CARD_STATEMENT_TOTALS above: every detail field this validator
        // produces is either money read off the statement, money summed from our own extracted
        // rows, a not-yet-allowlisted enum, or prose -- so nothing survives and writeDetails
        // returns null outright, not merely "no money leaked".
        User user = user();
        String reference = analysis(user.getId());

        int written = recorder.recordForAnalysis(reference, List.of(creditCardFlowReconciliationWarning()));

        assertThat(written).isEqualTo(1);
        UUID sessionId = analysisRepository.findByReference(reference).orElseThrow().getId();
        var stored = findingRepository.findByAnalysisSessionIdOrderBySectionIndexAscRuleAsc(sessionId);
        assertThat(stored).extracting(ImportVerificationFinding::getRule)
                .containsExactly("CREDIT_CARD_FLOW_RECONCILIATION");
        assertThat(stored).extracting(ImportVerificationFinding::getOutcome).containsExactly("WARNING");

        assertThat(stored.get(0).getDetailsJson())
                .as("the outcome column already carries the one fact worth persisting for this rule")
                .isNull();
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
                "hash-" + UUID.randomUUID(), "objects/x", StatementUpload.Format.CSV);

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
