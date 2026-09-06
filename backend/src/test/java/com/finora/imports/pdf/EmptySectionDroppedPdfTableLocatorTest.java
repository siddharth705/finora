package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A section that closed with literally nothing collected -- zero rows, zero auxiliary text -- can
 * never represent real content. Confirmed on a real HDFC composite statement: a Fixed Deposit
 * block prints its own TWO-line caption header back to back (an 8-column band, immediately
 * followed with zero intervening text by the block's real 3-column accepted header). The first
 * band alone clears {@code looksLikeHeaderRow}'s own bar, so the header-diff split opens a section
 * for it -- which the very next line immediately closes again, having collected nothing at all.
 * {@link PdfTableLocator#dropCompletelyEmptySections} removes it.
 *
 * <p>Coordinates and shapes only, per the Synthetic Fixture Policy -- every value below is
 * invented.
 */
class EmptySectionDroppedPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void backToBackHeaderBands_dropsTheEmptyPhantomBetweenThem() {
        List<PositionedText> runs = new ArrayList<>();
        // The real ledger's own header and one transaction row.
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Amount", 400f, 440f, 100f));
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("500.00", 400f, 440f, 120f));

        // First band: a caption-shaped header that, alone, still clears looksLikeHeaderRow's
        // match-count bar (a date-hint word, an amount-hint word).
        runs.add(run("Number", 30f, 80f, 140f));
        runs.add(run("Booking Date", 90f, 170f, 140f));
        runs.add(run("Principal", 180f, 240f, 140f));
        runs.add(run("Installment Amount", 250f, 350f, 140f));

        // Second band, zero intervening text: the block's real accepted header, immediately after.
        runs.add(run("Current Amount", 30f, 100f, 160f));
        runs.add(run("Maturity Date", 110f, 190f, 160f));
        runs.add(run("Withdrawable", 200f, 270f, 160f));
        runs.add(run("1", 30f, 40f, 180f));
        runs.add(run("02 Jun 2026", 110f, 190f, 180f));
        runs.add(run("300.00", 200f, 270f, 180f));

        DocumentContext ctx = new DocumentContext("PDF", "EmptySectionDroppedPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("EMPTY_SECTION_DROPPED");
        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(1).rows()).hasSize(1);
    }

    /** Guard: a document whose ONLY section closed empty must keep it -- dropping it here would
     *  skip every headerless-inference fallback, which has already had its own chance. */
    @Test
    void theLastRemainingSection_isNeverDropped_evenIfEmpty() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("Number", 30f, 80f, 100f));
        runs.add(run("Booking Date", 90f, 170f, 100f));
        runs.add(run("Principal", 180f, 240f, 100f));
        runs.add(run("Installment Amount", 250f, 350f, 100f));

        DocumentContext ctx = new DocumentContext("PDF", "EmptySectionDroppedPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .doesNotContain("EMPTY_SECTION_DROPPED");
    }
}
