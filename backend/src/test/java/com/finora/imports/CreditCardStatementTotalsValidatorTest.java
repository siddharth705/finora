package com.finora.imports;

import com.finora.imports.pdf.CreditCardSummaryExtractor.CreditCardSummaryEvidence;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardStatementTotalsValidatorTest {

    private final CreditCardStatementTotalsValidator validator = new CreditCardStatementTotalsValidator();

    // extractionMethod is irrelevant to this validator -- it only ever reads the six numbers,
    // never which strategy produced them -- so tests use GRID as an arbitrary, valid value.
    // cashAdvances and fees are both nullable, matching real statements that print no line for a
    // charge type they don't have (fees: common; cashAdvances: confirmed on a real AU statement).
    // conflictingFields is empty -- the extract()-level conflict path has its own tests.
    private static CreditCardSummaryEvidence summary(String previousBalance, String purchases,
            String cashAdvances, String fees, String paymentsAndCredits, String totalAmountDue) {
        return new CreditCardSummaryEvidence(
                new BigDecimal(previousBalance), new BigDecimal(purchases),
                cashAdvances == null ? null : new BigDecimal(cashAdvances),
                fees == null ? null : new BigDecimal(fees), new BigDecimal(paymentsAndCredits),
                new BigDecimal(totalAmountDue), CreditCardSummaryEvidence.ExtractionMethod.GRID,
                List.of());
    }

    @Test
    void verifiesWhenTheBillingEquationReconciles() {
        // 10000 + 5000 + 0 + 100 - 2000 = 13100
        var finding = validator.check(summary("10000", "5000", "0", "100", "2000", "13100"));

        assertThat(finding.rule()).isEqualTo("CREDIT_CARD_STATEMENT_TOTALS");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("difference")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void treatsAbsentFeesAsZeroRatherThanBlockingTheCheck() {
        // 10000 + 5000 + 0 - 2000 = 13000, with no fees line printed at all.
        var finding = validator.check(summary("10000", "5000", "0", null, "2000", "13000"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("fees")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void treatsAbsentCashAdvancesAsZeroRatherThanBlockingTheCheck() {
        // The real shape found on AU's statement: no "Cash Advances" line printed anywhere, not a
        // printed zero. 10000 + 5000 + 100 - 2000 = 13100, with no cash-advances line at all.
        var finding = validator.check(summary("10000", "5000", null, "100", "2000", "13100"));

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details().get("cashAdvances")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void reportsWarningRatherThanFailedWhenTheEquationDoesNotReconcile() {
        // 10000 + 5000 + 0 + 100 - 2000 = 13100, but the statement claims 13500 is due.
        var finding = validator.check(summary("10000", "5000", "0", "100", "2000", "13500"));

        assertThat(finding.outcome())
                .as("a mismatch here means our own extraction misread a summary field, not that a "
                        + "transaction is wrong -- WARNING, never FAILED")
                .isEqualTo("WARNING");
        assertThat((BigDecimal) finding.details().get("difference")).isEqualByComparingTo("400");
    }

    @Test
    void explanationExplicitlyClearsTheTransactionsRatherThanStayingSilentAboutThem() {
        var finding = validator.check(summary("10000", "5000", "0", "100", "2000", "13500"));

        String explanation = ((String) finding.details().get("explanation")).toLowerCase();
        assertThat(explanation)
                .as("a reader seeing WARNING on a credit-card statement should not have to guess "
                        + "whether their transactions are implicated -- this must say outright that "
                        + "they are not")
                .contains("does not implicate")
                .doesNotContain("transaction is wrong").doesNotContain("row is wrong")
                .doesNotContain("incorrect transaction");
    }

    @Test
    void reportsNotApplicableWhenTheSummaryPanelDidNotPrintEnoughFields() {
        var finding = validator.check(CreditCardSummaryEvidence.NONE);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details()).containsKey("reason");
    }

    @Test
    void reportsNotApplicableWhenSummaryIsNull() {
        var finding = validator.check(null);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void reportsNotApplicableWhenOnlySomeFieldsArePresent() {
        // Total amount due and previous balance printed, but no purchases/cash-advances/payments
        // breakdown -- not enough to compute either side of the equation.
        var summary = new CreditCardSummaryEvidence(new BigDecimal("10000"), null, null, null, null,
                new BigDecimal("13100"), CreditCardSummaryEvidence.ExtractionMethod.GRID, List.of());

        assertThat(validator.check(summary).outcome()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void aCrossStrategyConflictOutranksBothNotApplicableAndTheEquationCheck() {
        // A complete, internally-consistent equation (10000 + 5000 - 2000 = 13000) that would
        // otherwise be VERIFIED -- but GRID and INLINE_LABEL_VALUE disagreed on totalAmountDue
        // while extract() was assembling this evidence. The conflict must win regardless.
        var summary = new CreditCardSummaryEvidence(new BigDecimal("10000"), new BigDecimal("5000"),
                null, null, new BigDecimal("2000"), new BigDecimal("13000"),
                CreditCardSummaryEvidence.ExtractionMethod.GRID, List.of("totalAmountDue"));

        var finding = validator.check(summary);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details().get("conflictingFields")).isEqualTo(List.of("totalAmountDue"));
        assertThat((String) finding.details().get("reason")).contains("SUMMARY_EXTRACTION_CONFLICT");
        assertThat(finding.details())
                .as("a contested reading is not trusted enough to even attempt the equation math")
                .doesNotContainKey("difference");
    }
}
