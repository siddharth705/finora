package com.finora.imports;

import com.finora.imports.pdf.CreditCardSummaryExtractor.PrintedCreditCardSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardStatementTotalsValidatorTest {

    private final CreditCardStatementTotalsValidator validator = new CreditCardStatementTotalsValidator();

    private static PrintedCreditCardSummary summary(String previousBalance, String purchases,
            String cashAdvances, String fees, String paymentsAndCredits, String totalAmountDue) {
        return new PrintedCreditCardSummary(
                new BigDecimal(previousBalance), new BigDecimal(purchases), new BigDecimal(cashAdvances),
                fees == null ? null : new BigDecimal(fees), new BigDecimal(paymentsAndCredits),
                new BigDecimal(totalAmountDue));
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
        var finding = validator.check(PrintedCreditCardSummary.NONE);

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
        var summary = new PrintedCreditCardSummary(new BigDecimal("10000"), null, null, null, null,
                new BigDecimal("13100"));

        assertThat(validator.check(summary).outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
