package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ILLUSTRATIVE_BLOCK_SUPPRESSED: a fictional worked-example table must never be read as a real
 * transaction table.
 *
 * <p>Found on a real AU Small Finance Bank credit-card statement: a fee/interest-calculation
 * appendix, introduced by "The following illustration will indicate the method of calculating...",
 * contains worked-example tables with invented dates/amounts -- perfectly well-formed headers by
 * every existing rule (a date-hint cell, multiple HEADER_HINTS matches, passes the density check),
 * because they ARE real tables, just describing fictional example data. With nothing distinguishing
 * "real" from "illustrative," each one opened its own section via the header-signature-difference
 * fallback, producing garbage sections and -- because those sections were non-empty -- blocking the
 * document's real, differently-shaped transactions from ever being recovered.
 *
 * <p>Every fixture below is fully hand-synthesized -- invented column names, dates, and amounts --
 * per the Synthetic Fixture Policy; no value from the real document appears here.
 */
class IllustrativeBlockSuppressionTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static List<PositionedText> realHeaderAndRow() {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("Date", 30f, 30f, 200f));
        runs.add(run("Description", 100f, 60f, 200f));
        runs.add(run("Amount", 400f, 40f, 200f));
        runs.add(run("Balance", 480f, 40f, 200f));
        runs.add(run("01/01/2026", 30f, 55f, 220f));
        runs.add(run("COFFEE SHOP", 100f, 60f, 220f));
        runs.add(run("50.00", 400f, 30f, 220f));
        runs.add(run("9950.00", 480f, 40f, 220f));
        return runs;
    }

    private static List<PositionedText> illustrationMarker(float y) {
        return List.of(run("The following illustration will indicate the method of calculating fees:",
                30f, 320f, y));
    }

    private static List<PositionedText> fakeHeaderAndRow(float y) {
        List<PositionedText> runs = new ArrayList<>();
        runs.add(run("Date", 30f, 30f, y));
        runs.add(run("Fee Type", 100f, 50f, y));
        runs.add(run("Charge Amount", 400f, 60f, y));
        runs.add(run("05/12/2025", 30f, 55f, y + 20f));
        runs.add(run("Late Payment", 100f, 60f, y + 20f));
        runs.add(run("100.00", 400f, 30f, y + 20f));
        return runs;
    }

    @Test
    void realTableSurvivesAnIllustrativeAppendixAfterIt() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(realHeaderAndRow());
        positioned.addAll(illustrationMarker(250f));
        positioned.addAll(fakeHeaderAndRow(280f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).rows().get(0)).containsEntry("Amount", "50.00");
        // The fake table's vocabulary must never appear anywhere in the real section's rows.
        assertThat(doc.sections().get(0).rows().get(0)).doesNotContainKey("Fee Type");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("ILLUSTRATIVE_BLOCK_SUPPRESSED");
    }

    @Test
    void noRealSectionBeforeTheMarker_producesEmptySectionsNotGarbageOnes() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(illustrationMarker(100f));
        positioned.addAll(fakeHeaderAndRow(130f));
        positioned.addAll(fakeHeaderAndRow(200f)); // a second, differently-shaped fake table

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).isEmpty();
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("ILLUSTRATIVE_BLOCK_SUPPRESSED");
    }

    @Test
    void markerAppearingTwice_isIdempotent() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(illustrationMarker(100f));
        positioned.addAll(fakeHeaderAndRow(130f));
        positioned.addAll(illustrationMarker(200f)); // same appendix, repeated phrasing
        positioned.addAll(fakeHeaderAndRow(230f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).isEmpty();
        // Set semantics on DocumentContext.record -- appearing twice still records once.
        long occurrences = ctx.capabilities().stream()
                .filter(c -> c.capability().equals("ILLUSTRATIVE_BLOCK_SUPPRESSED"))
                .count();
        assertThat(occurrences).isEqualTo(1);
    }
}
