package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.BalanceChainValidator.Outcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validator's whole purpose is catching parses that every other stage reported as successful,
 * so these tests are written as the failure SHAPES rather than as method coverage: wrong amount,
 * wrong direction, dropped row, duplicated row. Each one is a real way an importer goes wrong, and
 * each breaks the statement's own arithmetic in a way nothing else was checking.
 */
class BalanceChainValidatorTest {

    private final BalanceChainValidator validator = new BalanceChainValidator();

    private StagedRow row(String date, String description, String amount, String type, String balanceAfter) {
        return new StagedRow(
                LocalDate.parse(date), description, new BigDecimal(amount), type,
                "Other", "default", null, false, null,
                balanceAfter == null ? null : new BigDecimal(balanceAfter));
    }

    /** The motivating statement, parsed correctly: one deposit and three premium debits. */
    private List<StagedRow> correctlyParsedStatement() {
        return List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));
    }

    @Test
    void verifiesAStatementWhoseRowsReconcile() {
        var result = validator.validate(correctlyParsedStatement());

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).isEmpty();
        assertThat(result.isVerified()).isTrue();
        assertThat(result.summary()).contains("verified");
    }

    @Test
    void catchesAWrongAmount_andNamesTheRowAndTheShortfall() {
        // Exactly the bug that prompted this: the unused Deposits column held 0.00, the amount
        // picker took it, and the real 436.00 premium was never reached. Every stage reported
        // success. The balance chain does not.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "0.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);

        var wrong = result.discrepancies().get(0);
        assertThat(wrong.rowIndex()).isEqualTo(2);
        assertThat(wrong.description()).isEqualTo("PMJJBY PREMIUM");
        assertThat(wrong.expectedBalance()).isEqualByComparingTo("24980.00");
        assertThat(wrong.actualBalance()).isEqualByComparingTo("24544.00");
        // "We are short by 436.00 here" -- the premium that was read as zero.
        assertThat(wrong.difference()).isEqualByComparingTo("-436.00");
    }

    @Test
    void catchesAWrongDirection() {
        // A deposit recorded as an expense moves the balance the wrong way, so the mismatch is
        // TWICE the amount -- a louder signal than the transaction merely being absent.
        var rows = List.of(
                row("2026-07-10", "OPENING SPEND", "1000.00", "EXPENSE", "9000.00"),
                row("2026-07-11", "SALARY", "25000.00", "EXPENSE", "34000.00"),
                row("2026-07-12", "RENT", "5000.00", "EXPENSE", "29000.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).description()).isEqualTo("SALARY");
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("50000.00");
    }

    @Test
    void catchesADroppedRow() {
        // The middle transaction never made it through. The balance jumps without an explanation.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        // One pair is below the minimum for a verdict on its own -- see MIN_PAIRS_FOR_A_VERDICT.
        // Reported as not-applicable rather than as a confident failure, which is the honest
        // answer from a single comparison.
        assertThat(result.status()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void catchesADroppedRow_whenThereIsEnoughChainToJudge() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                // PMJJBY (436.00) dropped entirely -- the next row's balance cannot be reached.
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.WARNING);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("-436.00");
    }

    @Test
    void catchesADuplicatedRow() {
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isNotEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).isNotEmpty();
    }

    @Test
    void reportsFailedWhenMostRowsAreWrong() {
        // A whole column misread breaks most rows, not a scattered few -- which is the distinction
        // between FAILED and WARNING. Three of four premiums read as zero here.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "0.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "0.00", "EXPENSE", "24544.00"),
                row("2026-07-18", "APY INSTALLMENT", "0.00", "EXPENSE", "24462.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.FAILED);
        assertThat(result.discrepancies()).hasSize(3);
        assertThat(result.summary()).contains("read incorrectly");
    }

    @Test
    void reportsNotApplicableWhenTheStatementHasNoRunningBalance() {
        // Plenty of real statements have no balance column. Claiming a verification that never
        // happened would be worse than saying so.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", null),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", null),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", null));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(result.rowsWithBalance()).isZero();
        assertThat(result.summary()).contains("no running-balance column");
    }

    @Test
    void withoutAnOpeningBalance_theFirstRowIsNeverChecked() {
        // Documents the limitation the anchor exists to remove: chaining consecutive pairs cannot
        // test the first row, because nothing precedes it. On the real statement this validator was
        // built for, the opening deposit is typed EXPENSE and the pair-only check still says
        // VERIFIED -- the error is in the one position the chain cannot see.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "EXPENSE", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"));

        assertThat(validator.validate(rows).status()).isEqualTo(Outcome.VERIFIED);
    }

    @Test
    void anOpeningBalanceCatchesAWrongFirstRow() {
        // Same rows, now anchored. 0.00 + 25000 credited reaches 25000; recorded as an expense it
        // reaches -25000, so the first row finally has something to fail against.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "EXPENSE", "25000.00"),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-16", "PMJJBY PREMIUM", "436.00", "EXPENSE", "24544.00"));

        var result = validator.validate(rows, new BigDecimal("0.00"));

        assertThat(result.status()).isNotEqualTo(Outcome.VERIFIED);
        assertThat(result.discrepancies()).hasSize(1);
        assertThat(result.discrepancies().get(0).rowIndex()).isZero();
        assertThat(result.discrepancies().get(0).difference()).isEqualByComparingTo("50000.00");
    }

    @Test
    void anOpeningBalanceStillVerifiesACorrectStatement() {
        var result = validator.validate(correctlyParsedStatement(), new BigDecimal("0.00"));

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
        assertThat(result.rowsChecked()).isEqualTo(4);
    }

    @Test
    void handlesAnEmptyStatementWithoutFailing() {
        assertThat(validator.validate(List.of()).status()).isEqualTo(Outcome.NOT_APPLICABLE);
        assertThat(validator.validate(null).status()).isEqualTo(Outcome.NOT_APPLICABLE);
    }

    @Test
    void ignoresRowsWithNoBalance_ratherThanTreatingThemAsBreaks() {
        // A mid-statement row without its own balance (a summary line, a fee note) must not be
        // reported as a discrepancy -- the chain simply skips it and continues.
        var rows = List.of(
                row("2026-07-10", "UPI CREDIT", "25000.00", "INCOME", "25000.00"),
                row("2026-07-16", "SOME NOTE", "0.00", "EXPENSE", null),
                row("2026-07-16", "PMSBY PREMIUM", "20.00", "EXPENSE", "24980.00"),
                row("2026-07-18", "APY INSTALLMENT", "82.00", "EXPENSE", "24898.00"));

        var result = validator.validate(rows);

        assertThat(result.status()).isEqualTo(Outcome.VERIFIED);
    }
}
