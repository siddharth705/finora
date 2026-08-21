package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardFlowReconciliationValidatorTest {

    private final CreditCardFlowReconciliationValidator validator = new CreditCardFlowReconciliationValidator();

    private static StagedRow row(String description, String amount, String type) {
        return new StagedRow(
                LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", "default", null, false, null, null);
    }

    // extractionMethod and conflictingFields are irrelevant to this validator -- it only ever
    // reads purchases()/paymentsAndCredits(), never which strategy produced them or whether the
    // two strategies disagreed on some OTHER field. GRID/empty-list used as arbitrary valid values,
    // matching CreditCardStatementTotalsValidatorTest's own summary() helper.
    private static CreditCardSummaryEvidence summary(String purchases, String paymentsAndCredits) {
        return new CreditCardSummaryEvidence(
                new BigDecimal("10000"), purchases == null ? null : new BigDecimal(purchases),
                null, null, paymentsAndCredits == null ? null : new BigDecimal(paymentsAndCredits),
                new BigDecimal("13100"), CreditCardSummaryEvidence.ExtractionMethod.GRID, List.of());
    }

    @Test
    void verifiesWhenExtractedTransactionsReconcileWithTheBillingSummary() {
        // Matches the real shape confirmed on AU's own statement: sum(EXPENSE) == purchases and
        // sum(INCOME) == paymentsAndCredits, exactly.
        var rows = List.of(
                row("GROCERY STORE", "3000", "EXPENSE"),
                row("FUEL STATION", "2000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.rule()).isEqualTo("CREDIT_CARD_FLOW_RECONCILIATION");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("evidenceLevel"))
                .isEqualTo("FULL_SUMMARY_RECONCILIATION");
    }

    @Test
    void warnsWhenTheAggregatesDisagreeWithTheBillingSummary() {
        // sum(EXPENSE) = 3000, but the statement's own printed purchases figure is 5000 -- a row
        // that should have been classified EXPENSE was likely misclassified as INCOME (or vice
        // versa), but this validator cannot say which.
        var rows = List.of(
                row("GROCERY STORE", "3000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("observedExpenseAmount"))
                .isEqualTo(new BigDecimal("3000"));
        assertThat(finding.details().get("expectedExpenseAmount"))
                .isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("differenceExpenseAmount"))
                .isEqualTo(new BigDecimal("-2000"));
        assertThat(finding.details().get("differenceIncomeAmount"))
                .isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void reportsFactualAmountEvidenceEvenWhenTheReconciliationSucceeds() {
        // The purpose is debugging real customer documents, not just explaining a WARNING -- the
        // amounts belong in details() on the VERIFIED path too.
        var rows = List.of(
                row("GROCERY STORE", "5000", "EXPENSE"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.details().get("expectedExpenseAmount")).isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("observedExpenseAmount")).isEqualTo(new BigDecimal("5000"));
        assertThat(finding.details().get("differenceExpenseAmount")).isEqualTo(BigDecimal.ZERO);
        assertThat(finding.details().get("expectedIncomeAmount")).isEqualTo(new BigDecimal("4000"));
        assertThat(finding.details().get("observedIncomeAmount")).isEqualTo(new BigDecimal("4000"));
        assertThat(finding.details().get("differenceIncomeAmount")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void neverAttributesTheMismatchToEitherSideOfTheDisagreement() {
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, summary("5000", "0"));

        String explanation = ((String) finding.details().get("explanation")).toLowerCase();
        assertThat(explanation)
                .as("a mismatch here means the aggregates disagree, not that a specific side is "
                        + "known to be wrong")
                .doesNotContain("wrong debit").doesNotContain("wrong credit")
                .doesNotContain("wrong debit/credit detected");
    }

    @Test
    void reportsNoSummaryWhenNoBillingPanelEvidenceWasExtractedAtAll() {
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, CreditCardSummaryEvidence.NONE);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_SUMMARY");
    }

    @Test
    void reportsNoSummaryWhenSummaryIsNull() {
        var finding = validator.check(List.of(), null);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_SUMMARY");
    }

    @Test
    void reportsPartialSummaryOnlyWhenThePanelIsReadableButMissesOneOfTheTwoFields() {
        // The real shape found on Axis's statement: a readable billing-summary panel that prints
        // a headline total but not a purchases/payments breakdown -- distinct from HDFC's
        // completely absent panel (NO_SUMMARY), even though both currently produce NOT_APPLICABLE.
        var rows = List.of(row("GROCERY STORE", "3000", "EXPENSE"));

        var finding = validator.check(rows, summary("5000", null));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("PARTIAL_SUMMARY_ONLY");
    }

    @Test
    void distinguishesPartialSummaryFromNoSummaryInTheReasonText() {
        var partial = validator.check(List.of(), summary(null, "4000"));
        var none = validator.check(List.of(), CreditCardSummaryEvidence.NONE);

        assertThat((String) partial.details().get("reason"))
                .isNotEqualTo(none.details().get("reason"));
    }

    @Test
    void reportsNoClassifiedTransactionsWhenTheSummaryIsFullButNoRowWasClassified() {
        // The scenario this state exists for: the billing-summary panel is completely readable
        // (both purchases and payments/credits printed), but zero rows extracted -- a different
        // product problem than "the bank doesn't print a summary" (NO_SUMMARY) and different again
        // from "the summary is only partial" (PARTIAL_SUMMARY_ONLY): here the transaction table
        // itself failed to extract or classify anything.
        var finding = validator.check(List.of(), summary("5000", "2000"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_CLASSIFIED_TRANSACTIONS");
    }

    @Test
    void noClassifiedTransactionsAlsoFiresWhenRowsExistButNoneAreExpenseOrIncome() {
        // "Zero rows" and "rows exist but none are classified" both mean there is nothing to sum on
        // either side -- both must resolve to the same evidence level.
        var rows = List.of(row("UNCLASSIFIED ROW", "999", "TRANSFER"));

        var finding = validator.check(rows, summary("5000", "2000"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("evidenceLevel")).isEqualTo("NO_CLASSIFIED_TRANSACTIONS");
    }

    @Test
    void allThreeNotApplicableReasonsAreDistinctText() {
        var noSummary = validator.check(List.of(), CreditCardSummaryEvidence.NONE);
        var partialSummary = validator.check(List.of(), summary(null, "4000"));
        var noClassifiedTransactions = validator.check(List.of(), summary("5000", "2000"));

        assertThat(List.of(
                noSummary.details().get("reason"),
                partialSummary.details().get("reason"),
                noClassifiedTransactions.details().get("reason")))
                .as("each NOT_APPLICABLE reason must be independently distinguishable -- a future "
                        + "failure-rate breakdown needs to tell these apart by reason text alone")
                .doesNotHaveDuplicates();
    }

    @Test
    void ignoresRowsOfNeitherExpenseNorIncomeType() {
        var rows = List.of(
                row("GROCERY STORE", "5000", "EXPENSE"),
                row("UNCLASSIFIED ROW", "999", "TRANSFER"),
                row("PAYMENT RECEIVED", "4000", "INCOME"));

        var finding = validator.check(rows, summary("5000", "4000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
    }
}
