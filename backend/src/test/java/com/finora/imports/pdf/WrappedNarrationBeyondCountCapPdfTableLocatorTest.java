package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WRAPPED_DESCRIPTION_BEYOND_COUNT_CAP: a transaction whose narration wraps onto a THIRD or later
 * physical line keeps those lines, and a trailing summary block printed at the same line spacing
 * does not get swallowed with them.
 *
 * <h2>Why the count cap alone was not enough</h2>
 *
 * {@code MAX_TRAILING_CONTINUATION_ROWS} is 2. {@link PdfTableLocator#continuesTheBlock} exists to
 * widen that by pitch, but its {@code separatesItsBlocks} guard first asks the document to prove it
 * leaves MORE space between transactions than inside one -- and a statement that sets every line on
 * a single uniform grid cannot prove it. Measured on real HDFC savings exports, every physical line
 * sits exactly 17.20pt below the one above, transaction rows and wrapped lines alike, so the pitch
 * check switched itself off on precisely the layout whose narration wraps most. The third wrapped
 * line then fell through to the leading-narration branch, was refused there, and was staged as its
 * own dateless row: a narration truncated mid-word, plus an unparseable row underneath it.
 *
 * <h2>Why proximity alone was not enough either</h2>
 *
 * Lifting the cap on {@link PdfTableLocator#belongsToTheRowAbove} alone regressed the corpus. That
 * predicate is scale-free -- it compares two gaps and says nothing about how large either is -- so
 * on a uniform grid it answers "belongs above" for a closing summary block exactly as readily as
 * for a genuine wrap. Measured, it absorbed nine lines of a statement-summary and disclaimer block
 * into the last real transaction of a Bandhan export, and elsewhere swallowed real transactions.
 *
 * <p>The signal that separates them is LEFT-EDGE ALIGNMENT: a cell that wraps re-starts every line
 * at the same x, because it is one cell. Measured across four real statements, every continuation
 * line of every transaction begins at a single x (118.79 on a Standard Chartered export, 201.41 on
 * a Bandhan export, 192.00 on an ICICI savings export; a real HDFC export is the only one with any
 * spread, and uses three values 6.00pt apart end to end). The text that must NOT be absorbed begins
 * somewhere else, because it belongs to a different cell or a different column.
 *
 * <p>Geometry below is modeled on those measurements; all text is synthetic per the Synthetic
 * Fixture Policy -- no narration, merchant, reference or amount is copied from any real document.
 */
class WrappedNarrationBeyondCountCapPdfTableLocatorTest {

    /** The uniform line pitch that disables the pitch check -- the real HDFC savings figure. */
    private static final float PITCH = 17.20f;
    private static final float HEADER_Y = 100.0f;

    private static final float DATE_X = 33.7f;
    private static final float NARRATION_X = 72.0f;
    private static final float REF_X = 283.5f;
    private static final float AMOUNT_X = 470.0f;
    private static final float BALANCE_X = 530.0f;

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static List<PositionedText> header() {
        return List.of(
                run("Date", 39.9f, HEADER_Y),
                run("Narration", 144.1f, HEADER_Y),          // label offset RIGHT of its own data
                run("Chq./Ref.No.", REF_X, HEADER_Y),
                run("Withdrawal Amt.", AMOUNT_X, HEADER_Y),
                run("Closing Balance", BALANCE_X, HEADER_Y));
    }

    /** One anchor row: date, the first line of its narration, a reference, an amount, a balance. */
    private static void anchor(List<PositionedText> runs, String date, String narration, String ref,
                               String amount, String balance, float y) {
        runs.add(run(date, DATE_X, y));
        runs.add(run(narration, NARRATION_X, y));
        runs.add(run(ref, REF_X, y));
        runs.add(run(amount, AMOUNT_X, y));
        runs.add(run(balance, BALANCE_X, y));
    }

    /** A wrapped continuation line: narration text only, at the narration column's own left edge. */
    private static void wrap(List<PositionedText> runs, String text, float y) {
        runs.add(run(text, NARRATION_X, y));
    }

    private static DocumentContext ctx() {
        return new DocumentContext("PDF", "test");
    }

    private static List<java.util.Map<String, String>> rowsOf(List<PositionedText> runs, DocumentContext ctx) {
        return new PdfTableLocator().locate(runs, ctx).rows();
    }

    private static String narrationOf(List<java.util.Map<String, String>> rows, int rowIndex) {
        return rows.get(rowIndex).getOrDefault("Narration", "");
    }

    @Test
    void aFourthWrappedNarrationLineIsKeptOnAUniformlySpacedStatement() {
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        anchor(runs, "01/05/26", "SAMPLE PAYEE ONE", "0000000000000001", "170.00", "9,830.00", y);
        wrap(runs, "CONTINUATION LINE ALPHA", y += PITCH);
        wrap(runs, "CONTINUATION LINE BRAVO", y += PITCH);
        wrap(runs, "CONTINUATION LINE CHARLIE", y += PITCH);   // the 3rd -- past the count cap
        wrap(runs, "CONTINUATION LINE DELTA", y += PITCH);     // the 4th
        anchor(runs, "02/05/26", "SAMPLE PAYEE TWO", "0000000000000002", "50.00", "9,780.00", y += PITCH);

        DocumentContext ctx = ctx();
        var rows = rowsOf(runs, ctx);

        assertThat(rows)
                .as("the two anchors and nothing else -- no continuation line becomes its own row")
                .hasSize(2);
        assertThat(narrationOf(rows, 0))
                .contains("CONTINUATION LINE CHARLIE")
                .contains("CONTINUATION LINE DELTA");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("WRAPPED_DESCRIPTION_BEYOND_COUNT_CAP");
    }

    @Test
    void aTrailingSummaryBlockAtTheSamePitchIsNotSwallowed() {
        // The regression that killed the first attempt: a closing summary block printed at the same
        // uniform pitch, whose label line carries no figure and so reads as narration-only. It
        // starts at the DATE column's left edge, not the narration column's, because it is a
        // different cell -- that is the whole discriminator.
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        anchor(runs, "01/05/26", "SAMPLE PAYEE ONE", "0000000000000001", "170.00", "9,830.00", y);
        wrap(runs, "CONTINUATION LINE ALPHA", y += PITCH);
        wrap(runs, "CONTINUATION LINE BRAVO", y += PITCH);
        runs.add(run("Statement Summary", DATE_X, y += PITCH));
        runs.add(run("Opening Balance", DATE_X, y += PITCH));
        runs.add(run("Total Credits", DATE_X, y += PITCH));
        runs.add(run("Total Debits", DATE_X, y += PITCH));

        var rows = rowsOf(runs, ctx());

        assertThat(narrationOf(rows, 0))
                .as("the block's own wrapped lines are kept")
                .contains("CONTINUATION LINE ALPHA")
                .contains("CONTINUATION LINE BRAVO");
        assertThat(narrationOf(rows, 0))
                .as("a summary label printed in a different column is not narration, however close")
                .doesNotContain("Opening Balance")
                .doesNotContain("Total Credits")
                .doesNotContain("Total Debits");
    }

    @Test
    void aDatelessRowCarryingAFigureStillObeysTheCountCap() {
        // isNarrationOnly's own guarantee, unchanged by this capability: proximity has no business
        // moving a row that carries a figure, because reassigning it changes what a transaction is
        // worth. One real corpus statement prints a second, genuinely dateless line per transaction
        // carrying its reference and amounts.
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        anchor(runs, "01/05/26", "SAMPLE PAYEE ONE", "0000000000000001", "170.00", "9,830.00", y);
        wrap(runs, "CONTINUATION LINE ALPHA", y += PITCH);
        wrap(runs, "CONTINUATION LINE BRAVO", y += PITCH);
        // A third dateless line that DOES carry a figure, at the narration column's own left edge.
        runs.add(run("CONTINUATION LINE CHARLIE", NARRATION_X, y += PITCH));
        runs.add(run("1,234.00", AMOUNT_X, y));

        var rows = rowsOf(runs, ctx());

        assertThat(narrationOf(rows, 0))
                .as("a figure-bearing dateless row is not merged past the count cap by alignment")
                .doesNotContain("CONTINUATION LINE CHARLIE");
    }

    @Test
    void aContinuationLineMisalignedWithTheBlockIsRefusedPastTheCap() {
        // The alignment test in isolation: same pitch, same narration-only shape, but a left edge
        // that does not match the one this block's own first continuation established.
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        anchor(runs, "01/05/26", "SAMPLE PAYEE ONE", "0000000000000001", "170.00", "9,830.00", y);
        wrap(runs, "CONTINUATION LINE ALPHA", y += PITCH);
        wrap(runs, "CONTINUATION LINE BRAVO", y += PITCH);
        // Well beyond BLOCK_NARRATION_LEFT_TOLERANCE from NARRATION_X.
        runs.add(run("MISALIGNED TRAILING TEXT", NARRATION_X + 60.0f, y += PITCH));

        assertThat(narrationOf(rowsOf(runs, ctx()), 0)).doesNotContain("MISALIGNED TRAILING TEXT");
    }

    @Test
    void theFirstTwoContinuationLinesAreUnaffectedByAlignment() {
        // Within the count cap nothing changed, and it must not: the first continuation is what
        // TEACHES the block its left edge, so requiring the edge before it is known would refuse
        // every block's first line and the edge would never be learned at all.
        List<PositionedText> runs = new ArrayList<>(header());
        float y = HEADER_Y + PITCH;
        anchor(runs, "01/05/26", "SAMPLE PAYEE ONE", "0000000000000001", "170.00", "9,830.00", y);
        wrap(runs, "CONTINUATION LINE ALPHA", y += PITCH);

        assertThat(narrationOf(rowsOf(runs, ctx()), 0)).contains("CONTINUATION LINE ALPHA");
    }
}
