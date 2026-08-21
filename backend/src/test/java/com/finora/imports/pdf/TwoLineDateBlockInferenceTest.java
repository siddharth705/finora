package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INFERRED_TWO_LINE_DATE_BLOCK: a transaction printed as a two-physical-line visual block instead
 * of a table row.
 *
 * <p>Found on a real AU Small Finance Bank credit-card statement: each transaction is a small card
 * -- day-of-month, merchant narration, and a currency-prefixed amount on one line; month+year and a
 * bare "Cr"/"Dr" direction marker on the line below it. Not a table at all: no shared column
 * anchors, and the date is split across two lines, so INFERRED_HEADERLESS_LAYOUT's own
 * same-row-date-and-amount requirement never matches this shape.
 *
 * <p>Every fixture below is fully hand-synthesized -- invented dates, merchants, and amounts -- per
 * the Synthetic Fixture Policy; no value from the real document appears here.
 */
class TwoLineDateBlockInferenceTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return run(text, endX - width, width, y);
    }

    /** A block laid out across exactly two physical lines -- the simple case, day/narration/amount
     *  sharing one y, month-year/direction sharing the next. */
    private static List<PositionedText> twoRowBlock(String day, String narration, String amountText,
                                                      String monthYear, String direction, float y) {
        List<PositionedText> block = new ArrayList<>();
        block.add(run(day, 40f, 15f, y));
        block.add(run(narration, 70f, narration.length() * 5.2f, y));
        block.add(amount(amountText, 510f, y));
        block.add(run(monthYear, 34f, 21f, y + 16f));
        block.add(run(direction, 74f, 8f, y + 16f));
        return block;
    }

    /** Mirrors the real document's own quirk: the amount's baseline sits a few points below the
     *  narration/day baseline, closer to groupIntoRows' 3.0pt ROW_Y_TOLERANCE than to the day/
     *  narration line -- splitting one visual line into two separate {@code rows} entries. This is
     *  the exact shape a real bug in twoLineBlockAt's first version was found and fixed against
     *  (windowStartY vs. dayCell.y() as the pooling reference). */
    private static List<PositionedText> splitBaselineBlock(String day, String narration, String amountText,
                                                             String monthYear, String direction, float y) {
        List<PositionedText> block = new ArrayList<>();
        block.add(run(narration, 70f, narration.length() * 5.2f, y));
        block.add(run(day, 40f, 15f, y + 3f));
        block.add(amount(amountText, 510f, y + 4f));
        block.add(run(direction, 74f, 8f, y + 18f));
        block.add(run(monthYear, 34f, 21f, y + 19f));
        return block;
    }

    private static List<PositionedText> fourBlockStatement() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(twoRowBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        positioned.addAll(twoRowBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));
        positioned.addAll(twoRowBlock("14", "ONLINE SUBSCRIPTION FEE", "₹150.00", "Jan 26", "Dr", 380f));
        positioned.addAll(twoRowBlock("20", "CASHBACK REWARD CREDIT", "+₹75.00", "Jan 26", "Cr", 420f));
        return positioned;
    }

    @Test
    void happyPath_fourBlocksProduceFourCorrectTransactions() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(fourBlockStatement(), ctx);

        assertThat(doc.sections()).hasSize(1);
        List<java.util.Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsEntry("Amount", "₹500.00").containsEntry("Type", "Dr");
        assertThat(rows.get(1)).containsEntry("Amount", "+₹20000.00").containsEntry("Type", "Cr");
        assertThat(rows.get(2)).containsEntry("Description", "ONLINE SUBSCRIPTION FEE");
        assertThat(rows.get(3)).containsEntry("Amount", "+₹75.00").containsEntry("Type", "Cr");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("INFERRED_TWO_LINE_DATE_BLOCK");
    }

    @Test
    void splitBaselineGeometry_stillPairsCorrectly() {
        // Regression coverage for the real bug: the amount and day/narration cells land in
        // DIFFERENT groupIntoRows entries because their baselines differ by a few points, exactly
        // as measured on the real document.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(splitBaselineBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        positioned.addAll(splitBaselineBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));
        positioned.addAll(splitBaselineBlock("14", "ONLINE SUBSCRIPTION FEE", "₹150.00", "Jan 26", "Dr", 380f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(3);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("INFERRED_TWO_LINE_DATE_BLOCK");
    }

    @Test
    void belowMinimumTransactionCount_bailsToZeroSections() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(twoRowBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        positioned.addAll(twoRowBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).isEmpty();
    }

    @Test
    void signMarkerContradiction_excludesOnlyThatBlock() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(twoRowBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        positioned.addAll(twoRowBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));
        // Contradiction: a "+"-prefixed (credit-shaped) amount paired with a "Dr" marker.
        positioned.addAll(twoRowBlock("14", "SUSPICIOUS ENTRY", "+₹150.00", "Jan 26", "Dr", 380f));
        positioned.addAll(twoRowBlock("20", "CASHBACK REWARD CREDIT", "+₹75.00", "Jan 26", "Cr", 420f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<java.util.Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(3);
        assertThat(rows).noneMatch(r -> "SUSPICIOUS ENTRY".equals(r.get("Description")));
    }

    @Test
    void gapBeyondBound_isNotPaired() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(twoRowBlock("07", "GROCERY MART PURCHASE", "₹500.00", "Jan 26", "Dr", 300f));
        positioned.addAll(twoRowBlock("09", "SALARY CREDIT RECEIVED", "+₹20000.00", "Jan 26", "Cr", 340f));
        // Same shape, but the month/year+direction line is 40pt below the day line -- beyond
        // TWO_LINE_BLOCK_MAX_GAP (24pt) -- so it must not be treated as this block's continuation.
        List<PositionedText> unpaired = new ArrayList<>();
        unpaired.add(run("14", 40f, 15f, 380f));
        unpaired.add(run("STALE ENTRY", 70f, 60f, 380f));
        unpaired.add(amount("₹150.00", 510f, 380f));
        unpaired.add(run("Jan 26", 34f, 21f, 420f));
        unpaired.add(run("Dr", 74f, 8f, 420f));
        positioned.addAll(unpaired);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        // Only the 2 well-formed blocks pair; below HEADERLESS_MIN_TRANSACTION_ROWS(3), so this
        // stays empty rather than staging a partial, under-evidenced result.
        assertThat(doc.sections()).isEmpty();
    }

    @Test
    void documentWhereHeaderlessInferenceAlreadySucceeds_neverReachesThisFallback() {
        // A minimal INFERRED_HEADERLESS_LAYOUT-shaped document (same-row date+amount, no header
        // vocabulary) -- that capability runs first and should claim the document.
        List<PositionedText> positioned = new ArrayList<>();
        String[] dates = {"01/01/2026", "02/01/2026", "03/01/2026"};
        String[] descs = {"GROCERY STORE PURCHASE MONTHLY", "SALARY CREDIT FROM EMPLOYER", "ELECTRICITY BILL PAYMENT"};
        String[] debits = {"500.00", "-", "300.00"};
        String[] credits = {"-", "20000.00", "-"};
        String[] balances = {"9500.00", "29500.00", "29200.00"};
        for (int i = 0; i < dates.length; i++) {
            float y = 300f + i * 20f;
            positioned.add(run(dates[i], 30f, 55f, y));
            positioned.add(run(dates[i], 95f, 55f, y));
            positioned.add(run(descs[i], 165f, descs[i].length() * 5.2f, y));
            positioned.add(amount(debits[i], 390f, y));
            positioned.add(amount(credits[i], 520f, y));
            positioned.add(amount(balances[i], 650f, y));
        }

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("INFERRED_HEADERLESS_LAYOUT")
                .doesNotContain("INFERRED_TWO_LINE_DATE_BLOCK");
    }
}
