package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code nearestColumn} places a run by comparing ITS OWN left edge against each header label's
 * anchor -- it has no notion of a run's right edge or of a column's overall data width. A real
 * narration cell is routinely extracted as SEVERAL separate runs (PDFBox splits on whitespace
 * inside a long string), and the LAST of those runs can have its own left edge sitting well past
 * the midpoint between the narration and reference labels -- close enough to the reference
 * column's own anchor that nearestColumn, looking at that one run in isolation, buckets it there
 * instead, gluing the tail of the narration onto the front of an otherwise-clean reference number.
 *
 * <p>Label/anchor coordinates below (144.18 for Narration, 283.53 for Chq./Ref.No., midpoint
 * 213.85, narration data left-aligned from 72.03) are measured from a real HDFC savings statement
 * family (Sanjay HDFC / Mann HDFC / HDFC sav / HDFC 3 month all share this exact header) --
 * coordinates only, per the Synthetic Fixture Policy; every narration/reference VALUE below is
 * invented. Confirmed against the real corpus (not committed here): before this fix, ~135 lines on
 * one such real statement had a narration word wrongly appended to the reference cell; after,
 * zero, with every date/amount/balance field byte-identical.
 *
 * <p>Retuning the anchor doesn't fix this -- moving it to the data's own left edge only shifts the
 * boundary to 177.8 on the real document and the same tokens are still stolen. Containment in a
 * measured span sidesteps the question rather than tuning it -- see {@code PdfTableLocator}'s own
 * {@code ColumnSpan} doc comment for the full measured history and the three real documents that
 * forced each of its guards.
 */
class ColumnSpanPlacementPdfTableLocatorTest {

    private static final float DATE_ANCHOR = 30.0f;
    private static final float NARRATION_ANCHOR = 144.18f;
    private static final float REFERENCE_ANCHOR = 283.53f;
    private static final float WITHDRAWAL_ANCHOR = 380.0f;
    private static final float BALANCE_ANCHOR = 460.0f;
    // The real boundary nearestColumn draws between Narration and Chq./Ref.No. -- past it, but
    // still well inside the narration column's own real data extent (72.03 to ~275 on the real
    // document), not between the two columns' actual data.
    private static final float WRONG_MIDPOINT = (NARRATION_ANCHOR + REFERENCE_ANCHOR) / 2f;
    // Past WRONG_MIDPOINT (213.85) and closer to the reference anchor (283.53) than to narration's
    // own (144.18) -- exactly where a trailing narration word's own run lands on the real document.
    private static final float TRAILING_WORD_X = 230.0f;

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    private static List<PositionedText> header() {
        return List.of(
                run("Date", DATE_ANCHOR, DATE_ANCHOR + 34f, 100f),
                run("Narration", NARRATION_ANCHOR, NARRATION_ANCHOR + 60f, 100f),
                run("Chq./Ref.No.", REFERENCE_ANCHOR, REFERENCE_ANCHOR + 70f, 100f),
                run("Withdrawal Amt.", WITHDRAWAL_ANCHOR, WITHDRAWAL_ANCHOR + 60f, 100f),
                run("Closing Balance", BALANCE_ANCHOR, BALANCE_ANCHOR + 70f, 100f));
    }

    /** One transaction row, narration split into TWO runs the way PDFBox actually extracts a long
     *  cell -- an opening phrase (left edge 72.03, well before {@link #WRONG_MIDPOINT}) and a
     *  trailing word (left edge {@link #TRAILING_WORD_X}, past it) -- then a short reference
     *  number, an amount and a balance. Date comes first so {@code bucketRow}'s own existing "date
     *  already has a value" redirect is what correctly anchors the opening phrase in Narration to
     *  begin with; this test is about what happens to the TRAILING run specifically. */
    private static List<PositionedText> row(float y, String narrationOpen, String narrationTail,
                                             String reference, String amount, String balance) {
        return List.of(
                run("05/05/26", DATE_ANCHOR, DATE_ANCHOR + 34f, y),
                run(narrationOpen, 72.03f, TRAILING_WORD_X - 2f, y),
                run(narrationTail, TRAILING_WORD_X, TRAILING_WORD_X + 30f, y),
                run(reference, REFERENCE_ANCHOR, REFERENCE_ANCHOR + 40f, y),
                run(amount, WITHDRAWAL_ANCHOR, WITHDRAWAL_ANCHOR + 30f, y),
                run(balance, BALANCE_ANCHOR, BALANCE_ANCHOR + 40f, y));
    }

    @Test
    void trailingNarrationWordStaysInNarration_notStolenByReference() {
        List<PositionedText> runs = new ArrayList<>(header());
        // Three sample rows (COLUMN_SPAN_MIN_SAMPLE) whose trailing word genuinely sits past
        // WRONG_MIDPOINT, so containment can measure Narration's real extent -- plus a fourth, the
        // one actually asserted on.
        runs.addAll(row(120f, "UPI-SAMPLE PAYEE ONE-000000000001", "FROM", "000111222333", "50.00", "469.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(140f, "UPI-SAMPLE PAYEE TWO-000000000002", "PHONE", "000111222334", "28.00", "441.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(160f, "UPI-SAMPLE PAYEE THREE-000000000003", "PHONE", "000111222335", "25.00", "416.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(180f, "UPI-SAMPLE PAYEE FOUR-000000000004", "LIMITED", "000111222336", "40.00", "376.40")); // synthetic-ok: invented reference number, not a real one

        DocumentContext ctx = new DocumentContext("PDF", "ColumnSpanPlacementPdfTableLocatorTest");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(4);
        // The mechanism actually fired -- not merely that the assertions below happen to pass.
        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("COLUMN_SPAN_PLACEMENT");

        // Precise check on the row under test: narration is the FULL printed phrase (opening
        // phrase and trailing word joined, exactly as bucketRow already joins same-column runs
        // with a space), and the reference cell holds only its own real value -- neither absorbed
        // a fragment of the other. Before this fix, the trailing run's OWN left edge (230.0) is
        // past WRONG_MIDPOINT (213.85) and closer to the reference anchor, so nearestColumn placed
        // it in Chq./Ref.No. by left-edge distance alone and every one of these four rows glued a
        // narration word onto the front of a bare reference number.
        Map<String, String> last = table.rows().get(3);
        assertThat(last.get("Narration")).isEqualTo("UPI-SAMPLE PAYEE FOUR-000000000004 LIMITED");
        assertThat(last.get("Chq./Ref.No.")).isEqualTo("000111222336"); // synthetic-ok: invented reference number, not a real one
        assertThat(last.get("Withdrawal Amt.")).isEqualTo("40.00");
        assertThat(last.get("Closing Balance")).isEqualTo("376.40");
    }

    /** Guards 1+2, directly: a date/amount/balance column is never a redirect target and its own
     *  value is never disturbed, even on the exact rows whose narration triggers containment. */
    @Test
    void dateAndAmountColumnsAreNeverTouchedByContainment() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(row(120f, "UPI-SAMPLE PAYEE ONE-000000000001", "FROM", "000111222333", "50.00", "469.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(140f, "UPI-SAMPLE PAYEE TWO-000000000002", "PHONE", "000111222334", "28.00", "441.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(160f, "UPI-SAMPLE PAYEE THREE-000000000003", "PHONE", "000111222335", "25.00", "416.40")); // synthetic-ok: invented reference number, not a real one

        var table = new PdfTableLocator().locate(runs, null);

        for (Map<String, String> bucketed : table.rows()) {
            assertThat(bucketed.get("Date")).isEqualTo("05/05/26");
            assertThat(bucketed.get("Withdrawal Amt.")).matches("\\d+\\.\\d{2}");
            assertThat(bucketed.get("Closing Balance")).matches("\\d+\\.\\d{2}");
        }
    }

    /** Guard 5: with fewer than {@code COLUMN_SPAN_MIN_SAMPLE} sample rows, containment must
     *  decline and nearestColumn's original (wrong) placement stands -- exactly the small, ragged
     *  table shape (an icici-style three-row credit-card ledger) that forced this guard. Proves
     *  the guard suppresses the fix, not merely that it compiles. */
    @Test
    void tooFewSampleRowsDeclinesContainment_originalPlacementStands() {
        List<PositionedText> runs = new ArrayList<>(header());
        // Only two rows -- one short of COLUMN_SPAN_MIN_SAMPLE (3) -- so no span is ever measured.
        runs.addAll(row(120f, "UPI-SAMPLE PAYEE ONE-000000000001", "FROM", "000111222333", "50.00", "469.40")); // synthetic-ok: invented reference number, not a real one
        runs.addAll(row(140f, "UPI-SAMPLE PAYEE TWO-000000000002", "PHONE", "000111222334", "28.00", "441.40")); // synthetic-ok: invented reference number, not a real one

        DocumentContext ctx = new DocumentContext("PDF", "ColumnSpanPlacementPdfTableLocatorTest");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .doesNotContain("COLUMN_SPAN_PLACEMENT");
        // Unfixed behaviour: nearestColumn places the trailing narration word in Chq./Ref.No. by
        // left-edge distance to WRONG_MIDPOINT, joined onto the row's own real reference value.
        for (Map<String, String> bucketed : table.rows()) {
            assertThat(bucketed.get("Chq./Ref.No.")).matches("(FROM|PHONE) \\d{12}");
        }
    }
}
