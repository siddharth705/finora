package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Geometry is copied from a direct {@link PositionedText} inspection of the real Axis credit-card
 * statement's payment-summary grid (the same panel {@code PaymentDueDateGridExtractorTest} already
 * documents); every DATE and AMOUNT here is invented, since the layout is what these cases are
 * about and the document's own values are not this repository's to carry.
 */
class StatementPeriodGridExtractorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    /**
     * The shape that makes this extractor necessary at all: on the real document the label is NOT
     * a run of its own. "Statement Period" arrives glued to its left-hand neighbour in a single
     * run spanning x=151.50-329.51, so no whole-run equality match can ever see it -- which is why
     * this matches a label CONTAINED in a run, unlike {@link PaymentDueDateGridExtractor}, whose
     * own label does arrive alone.
     */
    @Test
    void extract_readsThePeriodFromAGridLabelGluedToItsNeighbour() {
        var runs = List.of(
                run("Total Payment Due", 51.50f, 114.50f, 224.00f),
                run("Minimum Payment Due Statement Period", 151.50f, 329.51f, 224.00f),
                run("Payment Due Date", 381.00f, 443.01f, 224.00f),
                run("Statement Generation Date", 472.00f, 564.00f, 224.00f),
                run("01/06/2026 - 30/06/2026", 259.50f, 341.51f, 236.50f),
                run("20/07/2026", 393.00f, 431.01f, 236.50f),
                run("30/06/2026", 510.00f, 548.01f, 236.50f),
                // The two money values sit 1pt lower than the dates -- a real baseline jitter that
                // splits the grid's single visual value row into two buckets. The debit figure
                // x-overlaps the merged label run too, so this also proves the column match picks
                // the best-overlapping candidate rather than the first one it meets.
                run("12,345.67   Dr", 60.00f, 105.99f, 237.50f),
                run("500.00   Dr", 173.00f, 208.99f, 237.50f));

        var period = StatementPeriodGridExtractor.extract(runs);
        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void extract_readsTheLabelWhenItDoesArriveAsItsOwnRun() {
        var runs = List.of(
                run("Statement Period", 259.50f, 329.51f, 224.00f),
                run("14 Feb 2026 to 13 Mar 2026", 259.50f, 341.51f, 236.50f));

        var period = StatementPeriodGridExtractor.extract(runs);
        assertThat(period.start()).isEqualTo(LocalDate.of(2026, 2, 14));
        assertThat(period.end()).isEqualTo(LocalDate.of(2026, 3, 13));
    }

    /** A single date under the label is not a period. Committing half of one would leave the pair
     *  permanently half-null -- the same partial-parse hazard the line-based reader guards against
     *  and for the same reason: a half-known period reads as a stated fact downstream. */
    @Test
    void extract_refusesASingleDateUnderTheLabel() {
        var runs = List.of(
                run("Statement Period", 259.50f, 329.51f, 224.00f),
                run("20/07/2026", 259.50f, 300.00f, 236.50f));

        assertThat(StatementPeriodGridExtractor.extract(runs).start()).isNull();
    }

    /** "Statement Generation Date" and "Payment Due Date" are neighbours of the real label on the
     *  very same row; neither is a period and neither may be read as one. */
    @Test
    void extract_ignoresNeighbouringDateLabelsThatAreNotPeriods() {
        var runs = List.of(
                run("Payment Due Date Statement Generation Date", 381.00f, 564.00f, 224.00f),
                run("20/07/2026 30/06/2026", 393.00f, 548.01f, 236.50f));

        assertThat(StatementPeriodGridExtractor.extract(runs).start()).isNull();
    }

    /** A value too far below the label belongs to some other block, not this grid. */
    @Test
    void extract_refusesAValueBeyondTheGridsOwnRowGap() {
        var runs = List.of(
                run("Statement Period", 259.50f, 329.51f, 224.00f),
                run("01/06/2026 - 30/06/2026", 259.50f, 341.51f, 320.00f));

        assertThat(StatementPeriodGridExtractor.extract(runs).start()).isNull();
    }

    @Test
    void extract_returnsNoneForAnEmptyDocument() {
        assertThat(StatementPeriodGridExtractor.extract(List.of()).start()).isNull();
    }
}
