package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A continuation row's numeric fragment must never be allowed to silently change the value of an
 * amount column that already holds a complete, valid number.
 *
 * <p>{@code mergeInto}'s amount guard used to only refuse a merge when the MERGED text failed to
 * re-parse — on the theory that a merge which still parses cleanly must be safe. It isn't: a bare
 * numeric fragment (a stray reference-number digit run, a fee subtotal, a page-local tally)
 * mis-bucketed into an already-populated amount column merges into a DIFFERENT, still-perfectly
 * valid number — {@code "436.00" + " " + "5"} becomes {@code "436.00 5"}, which
 * {@code CsvParser.parseNumeric} later collapses (by stripping the space) into {@code 436.005} —
 * with no error, no flag, and no diagnostic anywhere in the pipeline.
 *
 * <p>The fix makes the amount guard unconditional, matching how the date guard immediately above
 * it in {@code mergeInto} has always worked: an already-valid amount is authoritative and is
 * never merged into, regardless of whether the merged text happens to still parse.
 */
class AmountColumnMergeProtectionPdfTableLocatorTest {

    private static final float HEADER_Y = 314.1f;

    /** Same real HDFC-measured header geometry as RightAlignedAmountColumnsPdfTableLocatorTest,
     *  reused so this fixture is exercising the same anchor geometry already proven correct. */
    private static List<PositionedText> header() {
        return List.of(
                run("Txn Date", 120.00f, 33.00f, HEADER_Y),
                run("Narration", 175.83f, 35.56f, HEADER_Y),
                run("Withdrawals", 295.83f, 47.12f, HEADER_Y),
                run("Deposits", 385.92f, 33.78f, HEADER_Y),
                run("Closing Balance", 472.98f, 62.24f, HEADER_Y));
    }

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    @Test
    void aNumericContinuationFragment_neverAltersAnAlreadyValidAmount() {
        List<PositionedText> runs = new java.util.ArrayList<>(header());
        runs.addAll(List.of(
                run("16/07/2026", 120.00f, 40.00f, 348.8f),
                run("PREMIUM DEBIT", 175.83f, 60.00f, 348.8f),
                run("436.00", 333.43f, 24.46f, 348.8f),
                run("0.00", 427.75f, 15.57f, 348.8f),
                run("24,544.00", 525.51f, 35.58f, 348.8f)));
        // A dateless continuation row whose only content is a bare digit that nearest-buckets
        // into the Withdrawals column -- e.g. a stray reference-number fragment or a fee
        // subtotal mis-bucketed by ordinary column geometry, exactly like the narration fragments
        // this same guard already protects the date column from.
        runs.add(run("5", 300.00f, 8.00f, 363.8f));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        // The amount is untouched -- not "436.00 5", and specifically not silently collapsed by a
        // later re-parse into 436.005.
        assertThat(table.rows().get(0)).containsEntry("Withdrawals", "436.00");
        // The fragment is not discarded either -- it lands in the description column, visible and
        // correctable, same as every other continuation fragment this file protects a structured
        // column from.
        assertThat(table.rows().get(0).get("Narration")).contains("5");
    }
}
