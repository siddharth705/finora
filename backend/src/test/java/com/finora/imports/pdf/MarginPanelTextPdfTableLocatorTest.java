package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MARGIN_PANEL_TEXT_EXCLUDED: a summary panel printed in the RIGHT MARGIN, at the same heights as
 * the transaction ledger, must not be bucketed into the ledger's rightmost column.
 *
 * <p>Every other correction in {@link PdfTableLocator#bucketRow} redirects a run BETWEEN columns.
 * None of them can express "this run belongs to no column at all", and {@code nearestColumn} has no
 * maximum-distance cap -- so a margin run sharing a physical row with a real transaction was
 * appended to whichever column was least far away, which is always the rightmost one.
 *
 * <p>Geometry below is measured from a real IndusInd credit-card statement; text is synthesized per
 * the Synthetic Fixture Policy -- no narration, merchant name, reference or amount is copied from
 * the source document. On that statement the ledger's five header cells end at x=55.2 (Date), 175.1,
 * 289.9, 352.4 and 417.9 (the rightmost, an amount column), while the margin panel's runs begin at
 * x=455.8 and beyond -- roughly 38pt of white space, with no horizontal overlap at all. The ledger's
 * own amounts are right-aligned to end at 422.6, so they START at 386-395: comfortably inside the
 * table, which is what makes the two separable by geometry rather than by a tolerance.
 *
 * <p>The concrete loss this caused: a margin label landed in a real transaction's amount cell,
 * making it "&lt;amount&gt; CR &lt;label&gt;". That string fails {@code CsvParser.parseNumeric}, so
 * {@code TransactionNormalizer} dropped the whole row -- and the document still classified
 * PARSED_COMPLETE, so a genuine transaction disappeared with nothing in any summary pointing at it.
 */
class MarginPanelTextPdfTableLocatorTest {

    private static final float HEADER_Y = 393.4f;

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The real statement's five column anchors and widths, ending at x=417.9. */
    private static List<PositionedText> header() {
        return List.of(
                run("Date", 40.9f, 14.3f, HEADER_Y),
                run("Transaction Details", 117.4f, 57.7f, HEADER_Y),
                run("Merchant Category", 232.3f, 57.6f, HEADER_Y),
                run("CRED Points", 316.1f, 36.3f, HEADER_Y),
                run("Amount (in `)", 376.0f, 41.9f, HEADER_Y));
    }

    @Test
    void aMarginPanelLabelIsNotAppendedToTheAmountCell() {
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000001", 72.4f, 122.0f, 420.0f),
                run("DEPARTMENTAL STORES", 232.3f, 80.0f, 420.0f),
                run("0", 330.0f, 3.4f, 420.0f),
                // Right-aligned amount: starts at 395.4, ends at 422.6 -- inside the table.
                run("12.00 CR", 395.4f, 27.2f, 420.0f),
                // The margin panel's label, ~2pt above the row's baseline so groupIntoRows merges
                // it into this same physical row, and 61pt clear of the rightmost column.
                run("Statement Date", 479.4f, 48.1f, 417.8f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(1);
        var row = table.rows().get(0);
        // The amount survives as a parseable value rather than "12.00 CR Statement Date".
        assertThat(row.get("Amount (in `)")).isEqualTo("12.00 CR");
        assertThat(row.get("Amount (in `)")).doesNotContain("Statement");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("MARGIN_PANEL_TEXT_EXCLUDED");
    }

    @Test
    void aNumberBeyondTheRightmostHeaderEndIsKept() {
        // Differential guard for the regression the first draft of this fix caused. A real Kotak
        // savings statement prints a Balance column whose header is NARROWER than the values under
        // it, so every right-aligned balance legitimately begins past the rightmost header end.
        // Excluding those replaced that document's closing balance with an earlier row's and turned
        // STATEMENT_TOTALS from VERIFIED to FAILED -- with the row count unchanged, so nothing
        // pointed at the wrong number. Only NON-numeric margin text may be excluded; a figure out
        // there is a right-aligned value overflowing its own header and must reach the redirect
        // rules. Header ends at 417.9; the balance below starts at 430.0, beyond it.
        List<PositionedText> runs = new ArrayList<>(header());
        runs.addAll(List.of(
                run("18/08/2026", 25.9f, 45.0f, 420.0f),
                run("UPI SAMPLE MERCHANT 000000000002", 72.4f, 122.0f, 420.0f),
                run("99,999.00", 430.0f, 40.0f, 420.0f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(String.join(" ", table.rows().get(0).values())).contains("99,999.00");
    }

    @Test
    void withoutMeasuredWidthsNothingIsExcluded() {
        // Hand-built fixtures and traces recorded before run widths existed carry width 0, so
        // headerEnds degenerates to a copy of headerAnchors. The rightmost ANCHOR sits in the
        // middle of the last column's own data, so treating it as the table's edge would discard
        // real values. The check must disable itself entirely in that case.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 40.9f, 0f, HEADER_Y),
                run("Description", 117.4f, 0f, HEADER_Y),
                run("Amount", 376.0f, 0f, HEADER_Y),
                run("18/08/2026", 25.9f, 0f, 420.0f),
                run("SAMPLE NARRATION", 117.4f, 0f, 420.0f),
                // Beyond the rightmost anchor (376.0) and non-numeric -- would be excluded if the
                // check ran without real widths.
                run("REF ABC123", 430.0f, 0f, 420.0f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        var table = new PdfTableLocator().locate(runs, ctx);

        assertThat(table.rows()).hasSize(1);
        assertThat(String.join(" ", table.rows().get(0).values())).contains("REF ABC123");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("MARGIN_PANEL_TEXT_EXCLUDED");
    }
}
