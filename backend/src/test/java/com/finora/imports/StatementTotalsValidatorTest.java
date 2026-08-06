package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This check exists to cover what the balance chain structurally cannot: its first row. So the
 * tests centre on that, and on the fact that a whole-statement mismatch has more than one possible
 * cause -- saying which is what makes the finding actionable rather than alarming.
 */
class StatementTotalsValidatorTest {

    private final StatementTotalsValidator validator = new StatementTotalsValidator();

    private StagedRow row(String description, String amount, String type, String balanceAfter) {
        return new StagedRow(
                LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", "default", null, false, null,
                balanceAfter == null ? null : new BigDecimal(balanceAfter));
    }

    /** The motivating statement, parsed correctly. Opening 0.00 -> closing 24,462.00. */
    private List<StagedRow> correctRows() {
        return List.of(
                row("UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"),
                row("APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));
    }

    @Test
    void verifiesAStatementWhoseTotalsReachTheClosingBalance() {
        var finding = validator.check(correctRows(), new BigDecimal("0.00"), new BigDecimal("24462.00"));

        assertThat(finding.rule()).isEqualTo("STATEMENT_TOTALS");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("totalCredits", new BigDecimal("25000.00"));
        assertThat(finding.details()).containsEntry("totalDebits", new BigDecimal("538.00"));
    }

    @Test
    void catchesAWrongFirstRow_whichTheBalanceChainCannotSee() {
        // The whole reason this rule exists. The opening deposit is typed EXPENSE; every LATER row
        // still chains correctly, so BalanceChainValidator reports VERIFIED. A total spanning the
        // document has no blind first position.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "EXPENSE", "25000.00"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"));

        assertThat(new BalanceChainValidator().validate(rows).status())
                .as("the chain cannot test its own first row")
                .isEqualTo(BalanceChainValidator.Outcome.VERIFIED);

        var finding = validator.check(rows, new BigDecimal("0.00"), new BigDecimal("24544.00"));

        assertThat(finding.outcome()).isEqualTo("FAILED");
    }

    @Test
    void blamesTheOpeningBalance_whenTheRowsReachTheStatedClosingBalance() {
        // The real HDFC case: rows are correct and the opening balance is misdetected as 50,000
        // where the document says 0.00. Blaming the transactions here would send someone to
        // re-read every line when one header field is at fault.
        var finding = validator.check(correctRows(), new BigDecimal("50000.00"), new BigDecimal("24462.00"));

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "OPENING_BALANCE");
        assertThat(finding.details().get("explanation").toString()).contains("opening balance that does not fit");
        // Negative: the stated closing is 50,000 BELOW what the inflated opening predicts, which
        // reads directly as "the opening balance is overstated by 50,000".
        assertThat(finding.details()).containsEntry("difference", new BigDecimal("-50000.00"));
    }

    @Test
    void blamesTheTransactions_whenTheyDoNotReachTheStatedClosingBalance() {
        // A premium read as zero: the rows no longer arrive at the closing balance the statement
        // prints, so the transactions are what is implicated, not the opening balance.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("PMJJBY PREMIUM", "0.00", "EXPENSE", "24980.00"));

        var finding = validator.check(rows, new BigDecimal("0.00"), new BigDecimal("24544.00"));

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "TRANSACTIONS");
    }

    @Test
    void reportsNotApplicableWithoutAnOpeningBalance() {
        // Inventing one -- from the first row's balance minus its amount, say -- would restate the
        // rows rather than check them, and evidence that cannot contradict is not evidence.
        var finding = validator.check(correctRows(), null, new BigDecimal("24462.00"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("reason").toString()).contains("opening balance");
    }

    @Test
    void reportsNotApplicableWithoutAClosingBalance() {
        var finding = validator.check(correctRows(), new BigDecimal("0.00"), null);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details().get("reason").toString()).contains("closing balance");
    }

    @Test
    void reportsNotApplicableWithNoRows() {
        var finding = validator.check(List.of(), new BigDecimal("0.00"), new BigDecimal("0.00"));

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
