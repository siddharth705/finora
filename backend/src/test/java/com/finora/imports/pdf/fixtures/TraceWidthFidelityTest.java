package com.finora.imports.pdf.fixtures;

import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.PdfTableLocator;
import com.finora.imports.pdf.PositionedText;
import com.finora.dto.ImportDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trace v3 carries run width, and this is what stops it being dropped again.
 *
 * <p>The corpus went three traces deep before anyone noticed that the format wrote
 * {@code page, x, y, text} and nothing else. Every run parsed back out had width 0 and
 * {@code endX() == x}, so {@link PdfTableLocator}'s right-edge correction — guarded on
 * {@code width() > 0} — was unreachable from any trace at any version.
 *
 * <p>That correction is not a nicety. Financial documents right-align amount columns, so within a
 * column the right edge is fixed and the left edge slides with the value's length: a short number
 * sits further right than a long one and can cross the midpoint into the next column purely by
 * having fewer digits. On a real HDFC statement that put "0.00" in Deposits, merged the row, turned
 * a Rs 25,000 deposit into an expense, and derived an opening balance of Rs 50,000 instead of
 * Rs 0.00. Three wrong numbers from one and a half points of text width.
 *
 * <p>So the corpus was structurally blind to the exact class of defect it exists to catch — silent
 * financial misattribution from column bucketing. A regression in that logic would have passed
 * every trace.
 *
 * <p>These tests are deliberately about the FORMAT rather than about any committed trace. The three
 * traces in the repository are v1 and stay width-blind until they are recaptured from their source
 * documents; nothing here can change that. What it can do is guarantee that a trace captured from
 * today onwards carries the evidence, and fail loudly if the column is ever dropped again.
 */
class TraceWidthFidelityTest {

    private static final TraceMetadata META = new TraceMetadata(
            TraceMetadata.CURRENT_TRACE_VERSION, PdfTraceRedactor.REDACTOR_VERSION, "TEST",
            "2026-08-07", "TraceWidthFidelityTest", List.of(), List.of(),
            "pins width through the capture format", List.of());

    @Test
    void widthSurvivesTheRoundTrip() {
        List<PositionedText> original = List.of(
                new PositionedText("436.00", 333.43f, 100f, 0, 24.46f),
                new PositionedText("0.00", 342.32f, 120f, 0, 15.57f));

        List<PositionedText> reparsed = PdfTrace.parse(PdfTrace.format(original, META));

        assertThat(reparsed).hasSize(2);
        // The right edge is the coordinate the bucketing decision is made on, so it is the one worth
        // asserting rather than the width in isolation.
        assertThat(reparsed.get(0).endX()).isEqualTo(original.get(0).endX());
        assertThat(reparsed.get(1).endX()).isEqualTo(original.get(1).endX());
        assertThat(reparsed.get(0).width()).isGreaterThan(0f);
    }

    /** A v3 file has to say so in its magic line. A reader that cannot tell from the header whether
     *  widths are present has to guess from the body, and guessing is what a version exists to
     *  prevent. */
    @Test
    void aCapturedTraceDeclaresItselfV3() {
        String written = PdfTrace.format(List.of(new PositionedText("1.00", 10f, 10f, 0, 5f)), META);

        assertThat(written.lines().findFirst()).contains("# finora-pdf-trace v3");
        assertThat(written).contains("# page\tx\ty\twidth\ttext");
        assertThat(TraceMetadata.parse(written).traceVersion()).isEqualTo(3);
    }

    /** Older traces still parse, and still mean what they meant. Backward compatibility here is not
     *  politeness — the three committed traces are v1, and breaking them would trade a blind spot
     *  for an outage. */
    @Test
    void v1RowsStillParseAndStillCarryNoWidth() {
        String v1 = "# finora-pdf-trace v1\n# page\tx\ty\ttext\n0\t31.18\t88.83\tSOME TEXT\n";

        List<PositionedText> runs = PdfTrace.parse(v1);

        assertThat(runs).hasSize(1);
        assertThat(runs.get(0).text()).isEqualTo("SOME TEXT");
        assertThat(runs.get(0).width()).isZero();
        assertThat(runs.get(0).endX()).isEqualTo(runs.get(0).x());
    }

    /** A run whose text is entirely numeric is the one shape where a 4-field and a 5-field row could
     *  be confused. Resolved by field count, and asserted because getting it wrong would silently
     *  turn a text run into a width. */
    @Test
    void aNumericTextRunIsNotMistakenForAWidthColumn() {
        String v1WithNumericText = "# finora-pdf-trace v1\n# page\tx\ty\ttext\n0\t31.18\t88.83\t25000.00\n";

        List<PositionedText> runs = PdfTrace.parse(v1WithNumericText);

        assertThat(runs).singleElement().satisfies(run -> {
            assertThat(run.text()).isEqualTo("25000.00");
            assertThat(run.width()).isZero();
        });
    }

    /**
     * Not asserted here: that the right-edge correction actually fires end to end.
     *
     * <p>It needs a table the locator will recognise — headers, an anchored date column, an amount
     * column it will accept — and hand-building that geometry produces a fixture that proves the
     * fixture rather than the format. The four tests above prove what this change is responsible
     * for: a captured trace carries width, declares itself v3, and older traces still mean what
     * they meant.
     *
     * <p>The end-to-end proof arrives with the first v3 capture, and it arrives automatically:
     * {@code CapabilityCorpusCoverageTest} lists RIGHT_ALIGNED_AMOUNTS in its shortfall, and the
     * ratchet turns that line red the moment a trace exercises it. That is a better proof than a
     * synthetic one anyway — it comes from a real document.
     */
}
