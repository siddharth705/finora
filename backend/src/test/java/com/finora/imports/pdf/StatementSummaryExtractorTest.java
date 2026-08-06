package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the totals a statement prints about itself.
 *
 * <p>The geometry is measured from a real HDFC statement — coordinates only, none of its content.
 * Its summary block is two label/value pairs stacked, which is the layout that matters most here:
 * the totals and the counts sit under SEPARATE label rows, and only the second one says "Count".
 *
 * <pre>
 *   y=442.4   Opening Balance   Debit Amount   Credit Amount   Closing Balance
 *   y=460.5   0.00              538.00         25,000.00       24,462.00
 *   y=482.5                     Debit Count    Credit Count
 *   y=496.7                     3              1
 * </pre>
 */
class StatementSummaryExtractorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The real block, at its real positions. */
    private static List<PositionedText> hdfcSummaryBlock() {
        return new ArrayList<>(List.of(
                run("Opening Balance", 127.36f, 65.35f, 442.4f),
                run("Debit Amount", 226.87f, 52.44f, 442.4f),
                run("Credit Amount", 318.71f, 55.55f, 442.4f),
                run("Closing Balance", 448.35f, 62.24f, 442.4f),
                run("0.00", 133.45f, 15.57f, 460.5f),
                run("538.00", 243.35f, 24.46f, 460.5f),
                run("25,000.00", 337.55f, 35.58f, 460.5f),
                run("24,462.00", 445.88f, 35.58f, 460.5f),
                run("Debit Count", 226.87f, 45.33f, 482.5f),
                run("Credit Count", 318.71f, 48.44f, 482.5f),
                run("3", 243.35f, 4.45f, 496.7f),
                run("1", 337.55f, 4.45f, 496.7f)));
    }

    @Test
    void readsTotalsAndCountsFromAStackedSummaryGrid() {
        var summary = StatementSummaryExtractor.extract(hdfcSummaryBlock());

        assertThat(summary.debitTotal()).isEqualByComparingTo("538.00");
        assertThat(summary.creditTotal()).isEqualByComparingTo("25000.00");
        assertThat(summary.debitCount()).isEqualTo(3);
        assertThat(summary.creditCount()).isEqualTo(1);
    }

    @Test
    void readsTotalsEvenThoughOnlyTheOtherLabelRowSaysCount() {
        // The totals row names nothing a transaction table could not also name, so on its own it is
        // untrustworthy. It is trusted here because "Debit Count" appears elsewhere in the same
        // document. An earlier version checked each row in isolation and silently returned the
        // counts with both totals null -- a check that quietly does half its job.
        var summary = StatementSummaryExtractor.extract(hdfcSummaryBlock());

        assertThat(summary.debitTotal()).isNotNull();
        assertThat(summary.creditTotal()).isNotNull();
    }

    @Test
    void matchesEachValueToTheLabelAboveItRatherThanByOrder() {
        // "Opening Balance" and "Closing Balance" are not summary labels, so their values must not
        // be consumed positionally -- the first VALUE in the row is 0.00, and reading by order
        // would make the debit total 0.00 and hide every debit in the statement.
        var summary = StatementSummaryExtractor.extract(hdfcSummaryBlock());

        assertThat(summary.debitTotal()).isEqualByComparingTo("538.00");
    }

    @Test
    void refusesATransactionTableHeaderThatHappensToNameAmounts() {
        // A statement whose transaction columns are literally "Debit Amount"/"Credit Amount", with
        // a genuine summary elsewhere so the document-wide gate is open. The row under the header
        // is a transaction: it carries a date and a description, so it is not a value row, and
        // reading it would report one payment as the whole statement's debit total.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Narration", 120f, 40f, 100f),
                run("Debit Amount", 226.87f, 52.44f, 100f),
                run("Credit Amount", 318.71f, 55.55f, 100f),
                run("01/07/2026", 50f, 40f, 120f),
                run("SOME PAYMENT", 120f, 60f, 120f),
                run("111.00", 243.35f, 24.46f, 120f),
                run("0.00", 337.55f, 15.57f, 120f)));
        runs.addAll(hdfcSummaryBlock());

        var summary = StatementSummaryExtractor.extract(runs);

        assertThat(summary.debitTotal()).isEqualByComparingTo("538.00");
        assertThat(summary.creditTotal()).isEqualByComparingTo("25000.00");
    }

    @Test
    void readsNothingFromADocumentThatPrintsNoSummaryAtAll() {
        // Only a transaction table. Without a count or a "total" anywhere, the header's own
        // "Debit Amount" is not evidence of a summary, and inventing one would compare a correct
        // import against a number the bank never printed.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 50f, 20f, 100f),
                run("Debit Amount", 226.87f, 52.44f, 100f),
                run("Credit Amount", 318.71f, 55.55f, 100f),
                run("111.00", 243.35f, 24.46f, 120f),
                run("0.00", 337.55f, 15.57f, 120f)));

        assertThat(StatementSummaryExtractor.extract(runs).isEmpty()).isTrue();
    }

    @Test
    void reportsACountThatIsNotAWholeNumberAsAbsent() {
        // A rupee figure that drifted under a count label. Absent is honest; coercing "24,462.00"
        // to 24462 transactions would fail every import against a number that means something else.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Debit Count", 226.87f, 45.33f, 482.5f),
                run("24,462.00", 235.00f, 35.58f, 496.7f)));

        assertThat(StatementSummaryExtractor.extract(runs).debitCount()).isNull();
    }

    @Test
    void readsNothingFromAnEmptyDocument() {
        assertThat(StatementSummaryExtractor.extract(List.of()).isEmpty()).isTrue();
        assertThat(StatementSummaryExtractor.extract(null).isEmpty()).isTrue();
    }

    @Test
    void ignoresAValueRowTooFarBelowItsLabels() {
        // A label row at the foot of a page and unrelated numbers at the head of the next: not a
        // grid, and pairing them would invent a total out of adjacency.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Debit Count", 226.87f, 45.33f, 400f),
                run("3", 243.35f, 4.45f, 700f)));

        assertThat(StatementSummaryExtractor.extract(runs).debitCount()).isNull();
    }

    @Test
    void toleratesALabelPrintedWithATrailingColon() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Total Debits :", 226.87f, 55f, 400f),
                run("538.00", 243.35f, 24.46f, 420f)));

        assertThat(StatementSummaryExtractor.extract(runs).debitTotal())
                .isEqualByComparingTo(new BigDecimal("538.00"));
    }
}
