package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Placing right-aligned amounts by the edge that doesn't move with the value's length.
 *
 * <p>Column bucketing assigns a text run to whichever header anchor its LEFT edge is nearest. That
 * is the right question for left-aligned text — a date, a narration — and the wrong one for a
 * number, because financial documents right-align amount columns. Under right alignment the right
 * edge is fixed and the left edge slides with the value's width, so a SHORT number sits further
 * right than a long one in the SAME column. Given two adjacent amount columns, it can slide past
 * the midpoint and be bucketed into the neighbour purely for having fewer digits.
 *
 * <p>The geometry below is measured from a real HDFC statement (coordinates only — no document
 * content is reproduced here). Its withdrawals column values all end at x=357.89 while their left
 * edges run from 333.43 down to 342.32, and the midpoint to the deposits anchor is 340.88. Every
 * value cleared it except "0.00", which missed by 1.44 points — so a row with a zero withdrawal
 * and a 25,000 deposit collapsed into a single "0.00 25,000.00" deposits cell with no withdrawals
 * value at all.
 *
 * <p>The damage was not confined to that cell, which is why this is tested at the locator rather
 * than only through a preview: the merged cell made a deposit read as an expense, and because the
 * opening balance is derived by backing the first row's amount out of its running balance, a
 * statement that opens at 0.00 was detected as opening at 50,000.
 */
class RightAlignedAmountColumnsPdfTableLocatorTest {

    private static final float HEADER_Y = 314.1f;

    /** Header labels, at their real measured positions and widths. */
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
    void keepsAZeroWithdrawalOutOfTheDepositsColumn() {
        // The failing row. "0.00" starts at 342.32 -- PAST the 340.88 midpoint between the
        // withdrawals and deposits anchors -- but ends at 357.89 like every other withdrawal.
        List<PositionedText> runs = new java.util.ArrayList<>(header());
        runs.addAll(List.of(
                run("10/07/2026", 120.00f, 40.00f, 327.5f),
                run("UPI CREDIT", 175.83f, 50.00f, 327.5f),
                run("0.00", 342.32f, 15.57f, 327.5f),
                run("25,000.00", 407.73f, 35.58f, 327.5f),
                run("25,000.00", 525.51f, 35.58f, 327.5f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0))
                .containsEntry("Withdrawals", "0.00")
                .containsEntry("Deposits", "25,000.00");
    }

    @Test
    void stillPlacesLongerAmountsWhereTheyAlreadyWent() {
        // The rows that never broke: a longer value's left edge stays on the correct side of the
        // midpoint, so left-edge bucketing already handled them. They are here because a fix that
        // rescues the short value by disturbing these would be a bad trade.
        List<PositionedText> runs = new java.util.ArrayList<>(header());
        runs.addAll(List.of(
                run("16/07/2026", 120.00f, 40.00f, 348.8f),
                run("PREMIUM DEBIT", 175.83f, 60.00f, 348.8f),
                run("436.00", 333.43f, 24.46f, 348.8f),
                run("0.00", 427.75f, 15.57f, 348.8f),
                run("24,544.00", 525.51f, 35.58f, 348.8f)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0))
                .containsEntry("Withdrawals", "436.00")
                .containsEntry("Deposits", "0.00");
    }

    @Test
    void leavesRunsAloneWhenNoWidthWasMeasured() {
        // Zero width means endX == x, so the right-edge rule cannot fire and every caller that
        // predates the measurement -- hand-built fixtures, traces recorded before widths existed --
        // keeps exactly the behaviour it had. Asserted rather than assumed, because this is the
        // property that makes the change safe for the existing corpus: here the same "0.00" is
        // expected to land in Deposits, which is the OLD, wrong answer, and correctly so.
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                new PositionedText("Txn Date", 120.00f, HEADER_Y, 0),
                new PositionedText("Narration", 175.83f, HEADER_Y, 0),
                new PositionedText("Withdrawals", 295.83f, HEADER_Y, 0),
                new PositionedText("Deposits", 385.92f, HEADER_Y, 0),
                new PositionedText("Closing Balance", 472.98f, HEADER_Y, 0)));
        runs.addAll(List.of(
                new PositionedText("10/07/2026", 120.00f, 327.5f, 0),
                new PositionedText("UPI CREDIT", 175.83f, 327.5f, 0),
                new PositionedText("0.00", 342.32f, 327.5f, 0),
                new PositionedText("25,000.00", 407.73f, 327.5f, 0),
                new PositionedText("25,000.00", 525.51f, 327.5f, 0)));

        var table = new PdfTableLocator().locate(runs, null);

        assertThat(table.rows()).hasSize(1);
        assertThat(table.rows().get(0)).containsEntry("Deposits", "0.00 25,000.00");
    }
}
