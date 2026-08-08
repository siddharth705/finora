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

    @Test
    void reportsNotApplicableWithNoRows() {
        var finding = validator.check(List.of(), PRINTED);

        assertThat(finding.outcome()).isEqualTo("NOT_APPLICABLE");
    }
}
