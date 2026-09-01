package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The empty-cell "-" placeholder glyph, bucketed by its RIGHT edge like a real amount, not its
 * left edge like ordinary text.
 *
 * <p>{@link PdfTableLocator}'s RIGHT_ALIGNED_AMOUNTS correction (see
 * {@link RightAlignedAmountColumnsPdfTableLocatorTest}) already re-buckets a real amount by its
 * right edge when three amount columns sit close together, because right alignment means the
 * right edge is fixed per column while the left edge slides with the value's digit count. Before
 * this fix, that correction required {@code CsvParser.parseNumeric(text) != null} to even attempt
 * it — and {@code parseNumeric("-")} is deliberately {@code null} (a bare dash is CsvParser's own
 * "no value" marker), so the empty-cell placeholder never got the correction a real number did,
 * and stayed wherever its LEFT edge happened to land.
 *
 * <p>Geometry below is measured from a real Indian Overseas Bank (IOB) / SBI-branded savings
 * statement with adjacent Debit(Rs)/Credit(Rs)/Balance(Rs) columns — header anchors/ends
 * x=398.4/437.9 (Debit), 455.6/497.8 (Credit), 507.3/556.8 (Balance). Coordinates only, per the
 * Synthetic Fixture Policy — narration, reference numbers and amounts below are synthesized, not
 * copied from the source document.
 *
 * <p>This bank right-aligns its "-" placeholder exactly like a real amount: an empty Credit(Rs)
 * cell's "-" prints at x=495.8/endX=498.8, dead center on Credit's own header end (497.8) — but by
 * LEFT edge that is only 11.5pt from Balance(Rs)'s anchor (507.3) versus 40.2pt from its own
 * Credit anchor (455.6), so plain nearest-anchor bucketing put it in Balance(Rs), directly in
 * front of that row's real balance run. The two runs then glued together with a space (e.g.
 * "- 12,345.67"), which CsvParser.parseNumeric parses as a negative number -- a positive running
 * balance recorded as negative, failing BALANCE_CHAIN verification even though the row's actual
 * transaction amount and direction (Debit(Rs)) were correct and untouched.
 *
 * <p>The mirror case (an empty Debit(Rs) cell's "-" gluing onto a real Credit(Rs) value) is
 * exercised too: that "-" prints at x=436.9/endX=439.9, dead center on Debit's own header end
 * (437.9), but by left edge it is nearer Credit's anchor (455.6, 18.7pt) than its own (398.4,
 * 38.5pt).
 */
class EmptyCellDashPlaceholderPdfTableLocatorTest {

    private static final float HEADER_Y = 287.3f;

    private static List<PositionedText> header() {
        return List.of(
                run("Date", 49.7f, 34.5f, HEADER_Y),
                run("Particulars", 167.7f, 42.8f, HEADER_Y),
                run("Debit(Rs)", 398.4f, 39.5f, HEADER_Y),
                run("Credit(Rs)", 455.6f, 42.2f, HEADER_Y),
                run("Balance(Rs)", 507.3f, 49.5f, HEADER_Y));
    }

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    @Test
    void emptyCreditDashDoesNotGlueOntoTheRealBalance() {
        // Synthesized row shaped like the real one that surfaced this bug: Debit(Rs) has a value,
        // Credit(Rs) is empty (rendered as a bare "-"), Balance(Rs) has a value. Runs given in the
        // document's real left-to-right x order; narration/reference text is a synthetic
        // placeholder, not copied from any real statement.
        List<PositionedText> runs = new java.util.ArrayList<>(header());
        runs.addAll(List.of(
                run("01-Jan-26", 44.7f, 39.5f, 311.5f),
                run("UPI/000000000001/DR/TEST PAYEE", 112.1f, 129.7f, 310.5f),
                run("150.00", 415.1f, 24.8f, 315.5f),
                run("-", 495.8f, 3.0f, 315.5f),
                run("12,345.67", 521.8f, 36.0f, 315.5f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        var row = table.rows().get(0);
        assertThat(row).containsEntry("Debit(Rs)", "150.00");
        assertThat(row.get("Balance(Rs)")).doesNotContain("-").isEqualTo("12,345.67");
        // The placeholder itself is not lost -- it lands in its own real column, where
        // CsvParser.parseNumeric("-") already reads it as "no value", same as a genuinely blank
        // cell would.
        assertThat(row.get("Credit(Rs)")).isEqualTo("-");
    }

    @Test
    void emptyDebitDashDoesNotGlueOntoTheRealCredit() {
        // Mirror row: Debit(Rs) empty ("-"), Credit(Rs) and Balance(Rs) have values.
        List<PositionedText> runs = new java.util.ArrayList<>(header());
        runs.addAll(List.of(
                run("02-Jan-26", 44.7f, 39.5f, 357.6f),
                run("UPI/000000000002/CR/TEST PAYEE TWO", 112.1f, 207.1f, 356.6f),
                run("-", 436.9f, 3.0f, 361.6f),
                run("2,500.00", 467.3f, 31.5f, 361.6f),
                run("14,845.67", 521.8f, 36.0f, 361.6f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        var row = table.rows().get(0);
        assertThat(row.get("Credit(Rs)")).isEqualTo("2,500.00");
        assertThat(row.get("Balance(Rs)")).doesNotContain("-").isEqualTo("14,845.67");
        assertThat(row.get("Debit(Rs)")).isEqualTo("-");
    }

    @Test
    void leavesTheDashAloneWhenNoWidthWasMeasured() {
        // Same zero-width safety property RightAlignedAmountColumnsPdfTableLocatorTest asserts for
        // real numbers: width 0 means endX == x, so the right-edge correction cannot fire and a
        // hand-built fixture or a trace recorded before widths existed keeps its old behaviour --
        // here, the old (wrong) placement into Balance(Rs).
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                new PositionedText("Date", 49.7f, HEADER_Y, 0),
                new PositionedText("Particulars", 167.7f, HEADER_Y, 0),
                new PositionedText("Debit(Rs)", 398.4f, HEADER_Y, 0),
                new PositionedText("Credit(Rs)", 455.6f, HEADER_Y, 0),
                new PositionedText("Balance(Rs)", 507.3f, HEADER_Y, 0)));
        runs.addAll(List.of(
                new PositionedText("01-Jan-26", 44.7f, 311.5f, 0),
                new PositionedText("UPI/000000000001/DR/TEST PAYEE", 112.1f, 310.5f, 0),
                new PositionedText("150.00", 415.1f, 315.5f, 0),
                new PositionedText("-", 495.8f, 315.5f, 0),
                new PositionedText("12,345.67", 521.8f, 315.5f, 0)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0).get("Balance(Rs)")).isEqualTo("- 12,345.67");
    }
}
