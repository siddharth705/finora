package com.finora.imports.trust;

import com.finora.dto.ImportDto;
import com.finora.imports.ImportReliabilityStatus;
import com.finora.imports.RowAccountingValidator;
import com.finora.imports.SummaryTotalsValidator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The v1 hold conditions, and -- just as importantly -- the signals that must NOT hold.
 *
 * <p>Every "does not hold" case below is a deliberate scope decision: persist and observe first,
 * gate later, once real distributions exist. A test here that starts failing because some new
 * signal began holding imports is reporting a scope regression, not a bug in the test.
 */
class TrustPredicateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

    private static ImportDto.VerificationReport report(ImportDto.VerificationFinding... findings) {
        return new ImportDto.VerificationReport(List.of(findings), false, "NATIVE_PDF",
                ImportReliabilityStatus.CLEAN);
    }

    private static ImportDto.VerificationFinding summaryTotals(String outcome, String cause) {
        return new ImportDto.VerificationFinding(SummaryTotalsValidator.RULE, outcome,
                Map.of("suspectedCause", cause));
    }

    private static ImportDto.VerificationFinding droppedRows(String reason, int count) {
        return new ImportDto.VerificationFinding(RowAccountingValidator.RULE, "WARNING",
                Map.of("droppedTransactionCandidateReasons", Map.of(reason, count)));
    }

    /** Explicit type argument, not inference: {@code List.of(array)} spreads the array as varargs
     *  and yields a {@code List<LocalDate>} of two elements rather than one period. */
    private static List<LocalDate[]> period(LocalDate start, LocalDate end) {
        return List.<LocalDate[]>of(new LocalDate[]{start, end});
    }

    // --------------------------------------------------------------- condition 1: count mismatch

    @Test
    void holdsWhenPrintedAndParsedCountsDisagree() {
        for (String cause : List.of("DIRECTION", "ROW_GROUPING", "MISSING_OR_EXTRA_ROWS")) {
            HoldDecision decision = TrustPredicate.evaluate(
                    List.of(report(summaryTotals("FAILED", cause))), List.of(), TODAY);

            assertThat(decision.hold()).as(cause).isTrue();
            assertThat(decision.summary()).as(cause).contains("count");
        }
    }

    /**
     * The strongest count mismatch there is, and the one most likely to be missed.
     *
     * <p>{@code SummaryTotalsValidator} emits this cause with outcome WARNING, not FAILED -- its
     * own comment explains why (the financial data did not fail validation, it never arrived, and
     * WARNING is what the existing renderers already surface). A predicate that filtered on
     * {@code outcome == "FAILED"} would therefore ignore the case the validator itself calls "the
     * strongest evidence available that the read failed". This asserts it does not.
     *
     * <p>Why such an import is reachable at all: {@code ExtractionCheck.rejectIfNothingWasExtracted}
     * flattens every section into one whole-document view, so it throws only when the WHOLE
     * document staged nothing. A composite statement whose first section staged 50 rows and whose
     * second staged none -- while printing a summary claiming activity -- imports successfully
     * today, one account's transactions short. That is the failure class this condition catches.
     */
    @Test
    void holdsWhenTheDocumentClaimsActivityAndNothingWasStaged() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("WARNING",
                        SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED))),
                List.of(), TODAY);

        assertThat(decision.hold())
                .as("a section that printed activity and staged nothing is a lost account")
                .isTrue();
        assertThat(decision.summary()).contains("count");
    }

    /** An amounts-only mismatch is a different defect and is explicitly out of v1 scope: the
     *  document's own count reconciliation is the high-quality signal, not its arithmetic. */
    @Test
    void doesNotHoldWhenOnlyAmountsDisagree() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "AMOUNTS"))), List.of(), TODAY).hold())
                .isFalse();
    }

    /**
     * The causes are an allow-list, not "everything except AMOUNTS".
     *
     * <p>A cause added to {@code SummaryTotalsValidator} later must not begin quarantining imports
     * the moment it ships, before anyone has seen how often it fires. That is the same
     * observe-then-gate rule the excluded signals below follow, applied to a signal that does not
     * exist yet.
     */
    @Test
    void doesNotHoldOnACauseThisVersionDoesNotKnowAbout() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "SOME_FUTURE_CAUSE"))), List.of(), TODAY)
                .hold()).isFalse();
    }

    @Test
    void doesNotHoldOnASummaryTotalsFindingWithNoCause() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(new ImportDto.VerificationFinding(
                        SummaryTotalsValidator.RULE, "FAILED", Map.of()))), List.of(), TODAY)
                .hold()).isFalse();
    }

    // ----------------------------------------------------------- condition 2: dropped transaction

    @Test
    void holdsOnAConfirmedPreHeaderActivityCandidate() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(droppedRows("PRE_HEADER_ACTIVITY_CANDIDATE", 1))), List.of(), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.summary()).contains("dropped");
    }

    /** Any OTHER dropped-row reason is unproven and must not hold -- only the pre-header one has
     *  been confirmed against real documents to mean a genuinely lost transaction. */
    @Test
    void doesNotHoldOnOtherDroppedRowReasons() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(droppedRows("UNEXPLAINED_ROW", 3))), List.of(), TODAY).hold())
                .isFalse();
    }

    // ---------------------------------------------------------- condition 3: metadata integrity

    @Test
    void holdsWhenPeriodEndsBeforeItStarts() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.summary()).contains("period");
    }

    @Test
    void holdsWhenPeriodIsInTheFuture() {
        assertThat(TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)), TODAY).hold())
                .isTrue();
    }

    @Test
    void holdsWhenPeriodSpansMoreThan400Days() {
        assertThat(TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1)), TODAY).hold())
                .isTrue();
    }

    /** A statement covering today is normal -- a period is only "in the future" once it starts or
     *  ends after today, not when it reaches it. */
    @Test
    void doesNotHoldOnAPeriodEndingToday() {
        assertThat(TrustPredicate.evaluate(List.of(),
                period(TODAY.minusDays(30), TODAY), TODAY).hold()).isFalse();
    }

    /** 400 days is the boundary, and the boundary itself is allowed. */
    @Test
    void doesNotHoldOnAPeriodOfExactly400Days() {
        LocalDate start = LocalDate.of(2025, 1, 1);
        assertThat(TrustPredicate.evaluate(List.of(), period(start, start.plusDays(400)), TODAY)
                .hold()).isFalse();
    }

    /**
     * A missing period must never hold. Corpus data showed that would quarantine the majority of
     * otherwise-good imports -- the single most important negative case here.
     */
    @Test
    void doesNotHoldOnAMissingPeriod() {
        assertThat(TrustPredicate.evaluate(List.of(), period(null, null), TODAY).hold()).isFalse();
        assertThat(TrustPredicate.evaluate(List.of(), period(LocalDate.of(2026, 8, 1), null), TODAY)
                .hold()).isFalse();
        assertThat(TrustPredicate.evaluate(List.of(), period(null, LocalDate.of(2026, 8, 1)), TODAY)
                .hold()).isFalse();
    }

    // ------------------------------------------------------------------ explicit non-conditions

    /** Every one of these is a real signal the pipeline computes and v1 deliberately does NOT gate
     *  on. If any starts holding imports, that is a scope regression, not an improvement. */
    @Test
    void doesNotHoldOnSignalsExcludedFromV1() {
        ImportDto.VerificationReport ocrAndUncertainHeader = new ImportDto.VerificationReport(
                List.of(new ImportDto.VerificationFinding("BALANCE_CHAIN", "FAILED", Map.of()),
                        new ImportDto.VerificationFinding("COLUMN_AMBIGUITY", "WARNING", Map.of())),
                true, "OCR", ImportReliabilityStatus.NEEDS_ATTENTION);

        assertThat(TrustPredicate.evaluate(List.of(ocrAndUncertainHeader), List.of(), TODAY).hold())
                .as("OCR, column ambiguity, header uncertainty and balance chain are all v1 "
                        + "observe-only signals")
                .isFalse();
    }

    /** NEEDS_ATTENTION is the aggregate verdict, and it is not the gate. The predicate reads the
     *  specific findings, so that the two can be tuned independently. */
    @Test
    void doesNotHoldOnTheAggregateReliabilityStatusAlone() {
        assertThat(TrustPredicate.evaluate(
                List.of(new ImportDto.VerificationReport(List.of(), false, "NATIVE_PDF",
                        ImportReliabilityStatus.NEEDS_ATTENTION)),
                period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), TODAY).hold())
                .isFalse();
    }

    @Test
    void cleanExtractionDoesNotHold() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(new ImportDto.VerificationFinding(
                        SummaryTotalsValidator.RULE, "VERIFIED", Map.of()))),
                period(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)), TODAY).hold())
                .isFalse();
    }

    // --------------------------------------------------------------------- structured categories

    @Test
    void countMismatchCarriesTheCountMismatchCategory() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "ROW_GROUPING"))), List.of(), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.COUNT_MISMATCH);
    }

    /** Two sections, two different named causes, both COUNT_MISMATCH -- the category list must not
     *  report the same category twice just because two different reason sentences fired. */
    @Test
    void twoDifferentCountMismatchCausesStillProduceOneDeduplicatedCategory() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "ROW_GROUPING")),
                        report(summaryTotals("FAILED", "DIRECTION"))),
                List.of(), TODAY);

        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.COUNT_MISMATCH);
        assertThat(decision.reasons()).as("the two distinct sentences are still both kept").hasSize(2);
    }

    @Test
    void droppedTransactionCarriesTheDroppedTransactionCategory() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(droppedRows("PRE_HEADER_ACTIVITY_CANDIDATE", 1))), List.of(), TODAY);

        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.DROPPED_TRANSACTION);
    }

    @Test
    void periodIntegrityCarriesThePeriodIntegrityCategory() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), TODAY);

        assertThat(decision.categories()).containsExactly(TrustPredicate.Category.PERIOD_INTEGRITY);
    }

    @Test
    void releaseCarriesNoCategories() {
        assertThat(HoldDecision.RELEASE.categories()).isEmpty();
    }

    // ---------------------------------------------------------------------------- shape and nulls

    @Test
    void reasonsAccumulateWhenSeveralConditionsFire() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "ROW_GROUPING"))),
                period(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), TODAY);

        assertThat(decision.reasons()).hasSize(2);
        assertThat(decision.summary()).contains("count").contains("period");
    }

    /**
     * The summary is stored on {@code held_statements.trigger_summary} and read by an operator who
     * was not here when it fired. An empty or duplicated one wastes the only context they get.
     */
    @Test
    void theSummaryNamesEveryReasonOnce() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("FAILED", "DIRECTION")),
                        report(summaryTotals("FAILED", "DIRECTION"))),
                List.of(), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.reasons()).as("the same reason twice reads as two problems").hasSize(1);
    }

    @Test
    void releaseCarriesNoReasons() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(), List.of(), TODAY);

        assertThat(decision.hold()).isFalse();
        assertThat(decision.reasons()).isEmpty();
        assertThat(decision.summary()).isEmpty();
    }

    /**
     * Nulls arrive here in practice -- a CSV import has no verification reports, and a section can
     * carry no period at all. The predicate runs on the worker's success path, so throwing would
     * turn a merely-unverified import into a failed one.
     */
    @Test
    void toleratesNullsEverywhere() {
        assertThat(TrustPredicate.evaluate(null, null, TODAY).hold()).isFalse();

        List<ImportDto.VerificationReport> withNulls = new java.util.ArrayList<>();
        withNulls.add(null);
        withNulls.add(new ImportDto.VerificationReport(null, false, null, null));
        List<LocalDate[]> periodsWithNulls = new java.util.ArrayList<>();
        periodsWithNulls.add(null);

        assertThat(TrustPredicate.evaluate(withNulls, periodsWithNulls, TODAY).hold()).isFalse();
    }

    /**
     * A null clock disables only the future-period rule, rather than throwing.
     *
     * <p>The caller passes {@code LocalDate.now()}, so this should never happen -- but this runs on
     * the success path of an import that has already staged real rows, and the cost of being wrong
     * about "never" is converting a good import into a failed one. The other two period rules are
     * clock-independent and still apply.
     */
    @Test
    void aNullClockDisablesOnlyTheFuturePeriodRule() {
        assertThat(TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)), null).hold())
                .as("the future rule is the only one that needs a clock")
                .isFalse();

        assertThat(TrustPredicate.evaluate(List.of(),
                period(LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)), null).hold())
                .as("an inverted period is nonsense with or without a clock")
                .isTrue();
    }

    /** A finding with null details must not throw -- several validators emit exactly that. */
    @Test
    void toleratesFindingsWithNoDetails() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(new ImportDto.VerificationFinding(
                        SummaryTotalsValidator.RULE, "FAILED", null))), List.of(), TODAY)
                .hold()).isFalse();
    }
}
