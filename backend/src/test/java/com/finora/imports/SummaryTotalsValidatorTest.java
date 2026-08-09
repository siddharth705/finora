package com.finora.imports;

import com.finora.dto.ImportDto.StagedRow;
import com.finora.imports.pdf.StatementSummaryExtractor.PrintedSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The first check whose evidence comes from the bank rather than from our own reading of the
 * document, so the tests centre on what that buys: failures the other two rules cannot see, and
 * saying which KIND of mistake was made rather than only that the numbers disagree.
 *
 * <p>The figures throughout are the motivating HDFC statement's: 1 credit of 25,000 and 3 debits
 * totalling 538, closing at 24,462.
 */
class SummaryTotalsValidatorTest {

    private final SummaryTotalsValidator validator = new SummaryTotalsValidator();

    /** What that statement prints about itself. */
    private static final PrintedSummary PRINTED = new PrintedSummary(
            new BigDecimal("538.00"), new BigDecimal("25000.00"), 3, 1);

    private StagedRow row(String description, String amount, String type) {
        return new StagedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", "default", null, false, null, null);
    }

    private List<StagedRow> correctRows() {
        return List.of(
                row("UPI CREDIT", "25000.00", "INCOME"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE"),
                row("APY INSTALLMENT", "82.00", "EXPENSE"));
    }

    @Test
    void verifiesWhenEveryTotalAndCountMatchesWhatTheBankPrinted() {
        var finding = validator.check(correctRows(), PRINTED);

        assertThat(finding.rule()).isEqualTo("SUMMARY_TOTALS");
        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsEntry("printedDebitTotal", new BigDecimal("538.00"));
        assertThat(finding.details()).containsEntry("parsedDebitTotal", new BigDecimal("538.00"));
    }

    @Test
    void catchesADirectionError_theFailureThatStartedAllOfThis() {
        // The original bug: the 25,000 deposit read as an expense. Every row is present and every
        // rupee is accounted for, so the totals-vs-balances arithmetic has nothing to object to --
        // but the bank says 1 credit and 3 debits, and we produced 0 and 4.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "EXPENSE"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE"),
                row("APY INSTALLMENT", "82.00", "EXPENSE"));

        var finding = validator.check(rows, PRINTED);

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "DIRECTION");
        assertThat(finding.details().get("explanation").toString()).contains("wrong way");
    }

    @Test
    void blamesTheAmountsWhenTheCountsAgreeButTheMoneyDoesNot() {
        // A premium read as zero -- the ORIGINAL symptom on this statement. Right number of
        // transactions on each side, wrong value in one of them.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "INCOME"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE"),
                row("APY INSTALLMENT", "0.00", "EXPENSE"));

        var finding = validator.check(rows, PRINTED);

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "AMOUNTS");
        assertThat(finding.details().get("mismatches").toString()).contains("debitTotal");
    }

    @Test
    void blamesRowGroupingWhenTheMoneyAgreesButTheCountDoesNot() {
        // Three debits read as one combined 538.00. No sum can see this -- the totals are perfect
        // and the balance chain has nothing to compare a merged row against. Only the count shows it.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "INCOME"),
                row("COMBINED PREMIUMS", "538.00", "EXPENSE"));

        var finding = validator.check(rows, PRINTED);

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "ROW_GROUPING");
    }

    @Test
    void blamesMissingRowsWhenNeitherTheCountNorTheMoneyAgrees() {
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "INCOME"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE"));

        var finding = validator.check(rows, PRINTED);

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "MISSING_OR_EXTRA_ROWS");
    }

    /**
     * The explanation may only state what this validator can establish.
     *
     * <p>It receives an already-resolved {@code PrintedSummary} and cannot tell a statement that
     * printed no totals from one whose totals its caller declined to attribute — both arrive as
     * {@code NONE}. The reason therefore must not claim the document printed nothing.
     *
     * <p>Measured, not hypothetical: on a real HDFC composite statement that prints "Debit Count
     * 66 / Credit Count 9" and totals matching the parse exactly, this rule reported that the
     * statement did not print its own totals. The caller withholds a document-level summary on a
     * multi-section document, so the claim was false, and anyone acting on it would go looking for
     * a summary block sitting on page 1.
     *
     * <p>Asserted as an absence rather than a string match, so rewording cannot reintroduce the
     * claim in different words.
     */
    @Test
    void doesNotClaimTheStatementPrintedNoTotals_whenItCannotKnowThat() {
        var finding = validator.check(correctRows(), PrintedSummary.NONE);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        String reason = finding.details().get("reason").toString();
        assertThat(reason)
                .as("the validator cannot see the document, so it must not assert what the document printed")
                .doesNotContainIgnoringCase("did not print")
                .doesNotContainIgnoringCase("statement printed no")
                .doesNotContainIgnoringCase("no summary was printed");
        assertThat(reason)
                .as("and it must still say why there was no comparison")
                .containsIgnoringCase("no printed totals were available");
    }

    @Test
    void comparesOnlyTheFieldsTheStatementActuallyPrinted() {
        // Counts but no totals. The absent totals must not be reported as compared-and-passed, and
        // must not be treated as disagreeing with ours either -- silence is not evidence.
        var summary = new PrintedSummary(null, null, 3, 1);

        var finding = validator.check(correctRows(), summary);

        assertThat(finding.outcome()).isEqualTo("VERIFIED");
        assertThat(finding.details()).containsKey("printedDebitCount");
        assertThat(finding.details()).doesNotContainKey("printedDebitTotal");
        assertThat(finding.details()).doesNotContainKey("parsedDebitTotal");
    }

    @Test
    void stillCatchesADirectionErrorFromCountsAlone() {
        // Same partial evidence, and it is enough: the bug that motivated this work is visible
        // from the counts with no printed totals at all.
        var rows = List.of(
                row("UPI CREDIT", "25000.00", "EXPENSE"),
                row("PMSBY PREMIUM", "20.00", "EXPENSE"),
                row("PMJJBY PREMIUM", "436.00", "EXPENSE"),
                row("APY INSTALLMENT", "82.00", "EXPENSE"));

        var finding = validator.check(rows, new PrintedSummary(null, null, 3, 1));

        assertThat(finding.outcome()).isEqualTo("FAILED");
        assertThat(finding.details()).containsEntry("suspectedCause", "DIRECTION");
    }

    /**
     * The contradiction: the statement says money moved, and none of it reached the ledger.
     *
     * <p>This used to report NOT_APPLICABLE -- "no transactions were parsed", as though the absence
     * were the end of the matter. It is the opposite. When our own parse produces nothing there is
     * nothing to weigh the printed evidence against, which makes that evidence the ONLY thing left
     * that can say the read failed. Found on a real SBI statement printing 5 debits and 1 credit
     * totalling 45,000, which reached the user with no verification report at all.
     *
     * <p>WARNING, not FAILED: the amounts did not fail validation, they never arrived.
     */
    @Test
    void warnsWhenTheStatementClaimsActivityAndNothingWasStaged() {
        var finding = validator.check(List.of(), PRINTED, 66);

        assertThat(finding.outcome()).isEqualTo("WARNING");
        assertThat(finding.details())
                .containsEntry("suspectedCause", SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED)
                .containsEntry("stagedTransactionCount", 0)
                .containsEntry("printedDebitCount", 3)
                .containsEntry("printedCreditCount", 1);
    }

    /**
     * The distinction that makes the evidence worth carrying: "the table was read and every row of
     * it rejected" is a different failure from "no table was found at all", and both stage zero.
     * Only the located count tells them apart, and it is deliberately not the staged count.
     */
    @Test
    void recordsLocatedRowsSeparatelyFromStagedRows() {
        assertThat(validator.check(List.of(), PRINTED, 66).details())
                .as("the table was seen; every row of it was refused")
                .containsEntry("locatedRowCount", 66)
                .containsEntry("stagedTransactionCount", 0);

        assertThat(validator.check(List.of(), PRINTED, 0).details())
                .as("no table was seen at all -- same staged count, different failure")
                .containsEntry("locatedRowCount", 0)
                .containsEntry("stagedTransactionCount", 0);
    }

    /**
     * Zero staged rows is NOT itself the warning condition, and this is the guard that says so.
     * A dormant account's statement prints a summary of zeroes and has been read perfectly; raising
     * a contradiction there would train people to ignore the warning that matters.
     */
    @Test
    void doesNotWarnWhenTheStatementItselfReportsNoActivity() {
        var dormant = new PrintedSummary(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);

        var finding = validator.check(List.of(), dormant, 0);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
        assertThat(finding.details()).doesNotContainKey("suspectedCause");
    }

    /** No printed evidence and nothing staged is genuinely nothing to compare -- unchanged. */
    @Test
    void reportsNotApplicableWithNoRowsAndNoPrintedSummary() {
        var finding = validator.check(List.of(), PrintedSummary.NONE, 0);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
