package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Three fixes that only combine to fix a real Standard Chartered savings statement, none of them
 * individually sufficient:
 *
 * <ol>
 *   <li><b>WEAK_MONTH_DAY</b> ({@code PdfTableLocator.resolveYearlessDate}): the statement's
 *       transaction dates print month-first with no year at all ("May 01"), a shape the existing
 *       day-first {@code WEAK_DAY_MONTH} pattern (a real HSBC statement's own "30JUN") does not
 *       match. {@code hasDateValue} -- the row-anchor gate -- is threaded with a
 *       {@code candidateYears} set for exactly this reason; without it, no row registers as a
     *   transaction anchor at all, no matter how correctly its columns are bucketed.</li>
 *   <li><b>Row-cell reading order</b> ({@code PdfTableLocator.inReadingOrder}): a narration run
 *       and its OWN row's date value routinely differ by a fraction of a point in y (rendering
 *       jitter, not a real line break), and {@code groupIntoRows}' clustering sort used to leave
 *       them in THAT order rather than left-to-right. When the narration happened to sort first,
 *       it filled the date column before the real date value arrived, and
 *       {@code OFFSET_COLUMN_ANCHORS}' own "redirect once already occupied" heuristic never got
 *       the chance to run.</li>
 *   <li><b>Yearless-date awareness in OFFSET_COLUMN_ANCHORS</b>: even with reading order fixed,
 *       that redirect used to check only {@code CsvParser.parseDate}, which returns {@code null}
 *       for a yearless value -- so a date column already correctly holding "May 01" still read as
 *       "not yet occupied" to the redirect, and a narration run landing nearer that column than
 *       its own (this statement centers "Description"'s header label well right of where its own
 *       data actually starts) kept overwriting it instead of being redirected onward.</li>
 * </ol>
 *
 * <p>Real, unredacted coordinates and values (not lifted from the corpus -- see the Synthetic
 * Fixture Policy) chosen to reproduce all three: a "Value" column that wraps to "Value Date" (see
 * {@link WrappedHeaderOnAScoringLinePdfTableLocatorTest}'s own gate-1-exception test for that
 * half in isolation), a narration column whose data sits nearer the date column's anchor than its
 * own, and a physical row whose narration cell prints at a SMALLER y than its own date/value
 * cells despite being visually to their right.
 */
class MonthFirstYearlessDatePdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y, float width) {
        return new PositionedText(text, x, y, 0, width);
    }

    private List<PositionedText> documentRuns() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // A full, year-bearing date, printed once in the account summary as its own
                // isolated cell -- the candidateYears context yearsByPage/resolveYearlessDate
                // need. yearsByPage only ever extracts a year from a cell CsvParser.parseDate
                // itself already accepts as a WHOLE cell, so this cannot be embedded in a
                // sentence ("Statement Period 01 May 2026 to 31 May 2026" would not parse).
                run("01 May 2026", 50f, 20f, 60f),

                // Header (y=100): scores alone (Date + Description/Withdrawal/Balance all
                // recognized). "Description"'s anchor is pushed well right of where its own data
                // actually starts, below -- exactly the real statement's own layout.
                run("Date", 50f, 100f, 25f),
                run("Value", 90f, 100f, 25f),
                run("Description", 300f, 100f, 55f),
                run("Withdrawal", 420f, 100f, 45f),
                run("Balance", 490f, 100f, 30f),
                // Header wrap (y=108): a single cell, "Date", renaming "Value" to "Value Date" --
                // the one narrow exception to the strict floor's minimum-cell-count.
                run("Date", 92f, 108f, 25f),

                // Transaction 1 (y~140): every cell present, including its own "Date" column
                // value. The narration cell's own y (140.0) is the SMALLEST of the four -- sorts
                // first in a plain y-ascending order despite sitting well to the right.
                run("UPI SALARY CREDIT REF001", 150f, 140.0f, 130f),
                run("15,500.00", 490f, 140.4f, 40f),
                run("May 01", 50f, 140.5f, 30f),
                run("May 01", 92f, 140.6f, 30f),

                // Transaction 2 (y~160): NO value at all in the plain "Date" column -- exactly
                // the real statement's own alternating shape, where most rows carry their date
                // ONLY under "Value Date". Same y-jitter shape as transaction 1.
                run("UPI GROCERY STORE REF002", 150f, 160.0f, 130f),
                run("1,200.00", 420f, 160.2f, 35f),
                run("14,300.00", 490f, 160.4f, 40f),
                run("May 02", 92f, 160.6f, 30f)));

        return runs;
    }

    private PdfTableLocator.LocatedDocument locate(DocumentContext ctx) {
        return new PdfTableLocator().locateAll(documentRuns(), ctx);
    }

    @Test
    void theWrapRenamesValueToValueDate() {
        DocumentContext ctx = new DocumentContext("PDF", "MonthFirstYearlessDateTest");
        locate(ctx);

        assertThat(ctx.buildMetadata().headers()).contains("Value Date").doesNotContain("Value");
    }

    @Test
    void bothTransactionsBecomeTheirOwnRows_notOneCollapsedRow() {
        // Before all three fixes: hasDateValue never recognized "May 01" as a date at all, so
        // NEITHER row ever became a transaction anchor -- everything collapsed into a single
        // dateless row (or was silently dropped). Two anchors, one per transaction, is the
        // headline fix.
        List<Map<String, String>> rows = locate(null).sections().get(0).rows();

        assertThat(rows).hasSize(2);
    }

    @Test
    void theNarrationLandsUnderDescription_notUnderValueDate() {
        // The row-ordering + yearless-awareness fix, specifically: even once both rows are
        // recognized as anchors, the narration text must not overwrite the date value it shares
        // a physical row with. Both symptoms of the SAME underlying corruption -- a narration
        // that wins the date column loses its own transaction's real date.
        //
        // The stored value is "01 May 2026", not the raw "May 01" -- substituteYearlessDates
        // resolves and reformats it (dd MMM yyyy, the shape CsvParser.parseDate already accepts)
        // before bucketing, so TransactionNormalizer downstream never has to parse a yearless
        // value itself. See that method's own doc comment.
        List<Map<String, String>> rows = locate(null).sections().get(0).rows();

        assertThat(rows.get(0)).containsEntry("Value Date", "01 May 2026");
        assertThat(rows.get(0).get("Description")).contains("UPI SALARY CREDIT REF001");
        assertThat(rows.get(1)).containsEntry("Value Date", "02 May 2026");
        assertThat(rows.get(1).get("Description")).contains("UPI GROCERY STORE REF002");
    }

    @Test
    void secondTransactionsDateSurvivesEvenWithNoPlainDateColumnValueAtAll() {
        // Transaction 2 carries no value in the plain "Date" column at all -- the real
        // statement's own predominant shape. hasDateValue has to recognize "Value Date" alone.
        Map<String, String> second = locate(null).sections().get(0).rows().get(1);

        assertThat(second).doesNotContainKey("Date");
        assertThat(second).containsEntry("Value Date", "02 May 2026");
    }
}
