package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Physical Row Formation Evidence -- Input Fate Accounting's next layer, deliberately scoped
 * BEFORE header/table detection rather than at it. See {@code PdfTableLocator.PhysicalRowFormationEvidence}'s
 * own doc comment for why: a real ICICI CC statement's header formed incorrectly not because
 * header selection chose wrong (there is no such step), but because {@code groupIntoRows} had
 * already fused an unrelated summary-panel heading into the real header's own row -- two text runs
 * 2.3pt apart in y, inside the 3.0pt row-grouping tolerance -- before header logic ever ran. This
 * asserts the raw measurement that would have made that fact visible, not a verdict about it: no
 * validator reads this evidence yet (see "Evidence before capability" -- real-corpus measurement
 * while building this found a working document (AU, 2.9pt) sitting almost on top of the one
 * confirmed-broken document (ICICI CC, 3.0pt) on {@code maxPhysicalRowVerticalExtent} alone, which
 * is exactly why this field is named for the measurement, not for a verdict, and why no threshold
 * is inferred from it here).
 *
 * <p>Fixtures are fully hand-synthesized per the Synthetic Fixture Policy -- invented labels and
 * an invented y-gap, not the real document's own 2.3pt/373.9/376.2 figures, reproducing the same
 * SHAPE (a near-tolerance row-grouping collision) rather than the same numbers.
 */
class PhysicalRowFormationEvidenceTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    @Test
    void aCleanSingleBaselineDocumentHasZeroVerticalExtentAndAUniformCellCount() {
        // Every cell of every row shares exactly one y -- the common, well-behaved case, confirmed
        // against a real (clean) document's own header and data rows before this test was written.
        // Both rows carry exactly 3 cells, so the average and the maximum agree -- the shape a
        // uniform, well-formed document takes.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 30f, 40f, 100f));
        positioned.add(run("Description", 90f, 90f, 100f));
        positioned.add(run("Amount", 300f, 60f, 100f));
        positioned.add(run("01/01/2026", 30f, 55f, 120f));
        positioned.add(run("GROCERY STORE", 90f, 80f, 120f));
        positioned.add(run("500.00", 300f, 55f, 120f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        PdfTableLocator.PhysicalRowFormationEvidence evidence = doc.physicalRowFormationEvidence();
        assertThat(evidence.textRuns()).isEqualTo(6);
        assertThat(evidence.physicalRowsCreated()).isEqualTo(2);
        assertThat(evidence.totalPhysicalCells()).isEqualTo(6);
        assertThat(evidence.averageCellsPerRow()).isEqualTo(3.0, within(0.001));
        assertThat(evidence.maxCellsInRow()).isEqualTo(3);
        assertThat(evidence.maxPhysicalRowVerticalExtent()).isZero();
        assertThat(evidence.cellCountDistribution()).isEqualTo(Map.of(3, 2));
    }

    @Test
    void twoUnrelatedElementsWithinTheRowGroupingToleranceProduceANonzeroVerticalExtent() {
        // Reproduces the real ICICI CC shape at invented coordinates: a genuine 3-column header at
        // one y, plus one unrelated label (an invented "PANEL HEADING", standing in for the real
        // document's "SPENDS OVERVIEW") 2.5pt above it -- inside groupIntoRows' 3.0pt tolerance, so
        // it is folded into the same physical row as the real header, exactly as it was for the
        // real document, before header detection ever runs.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("PANEL HEADING", 30f, 90f, 200.0f));
        positioned.add(run("Date", 200f, 40f, 202.5f));
        positioned.add(run("Description", 260f, 90f, 202.5f));
        positioned.add(run("Amount", 400f, 60f, 202.5f));
        positioned.add(run("01/01/2026", 200f, 55f, 230f));
        positioned.add(run("GROCERY STORE", 260f, 80f, 230f));
        positioned.add(run("500.00", 400f, 55f, 230f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        PdfTableLocator.PhysicalRowFormationEvidence evidence = doc.physicalRowFormationEvidence();
        assertThat(evidence.maxCellsInRow())
                .as("PANEL HEADING joined the real header's own row -- 4 cells, not 3")
                .isEqualTo(4);
        assertThat(evidence.maxPhysicalRowVerticalExtent())
                .as("the merged row's members span 200.0 to 202.5 -- a real, measurable 2.5pt extent")
                .isEqualTo(2.5f);
        assertThat(evidence.cellCountDistribution())
                .as("the merged 4-cell row does not recur -- reproducing the real ICICI CC shape, "
                        + "where the merged row's own size appeared nowhere else in the document")
                .isEqualTo(Map.of(4, 1, 3, 1));
    }

    @Test
    void aGapBeyondTheRowGroupingToleranceNeverMerges() {
        // The mirror case: the same invented panel heading, but far enough above (5pt, past the
        // 3.0pt tolerance) that groupIntoRows correctly keeps it as its own separate row -- proving
        // the evidence reflects an actual merge, not just "two things existed near each other".
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("PANEL HEADING", 30f, 90f, 197f));
        positioned.add(run("Date", 200f, 40f, 202f));
        positioned.add(run("Description", 260f, 90f, 202f));
        positioned.add(run("Amount", 400f, 60f, 202f));
        positioned.add(run("01/01/2026", 200f, 55f, 230f));
        positioned.add(run("GROCERY STORE", 260f, 80f, 230f));
        positioned.add(run("500.00", 400f, 55f, 230f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        PdfTableLocator.PhysicalRowFormationEvidence evidence = doc.physicalRowFormationEvidence();
        assertThat(evidence.maxCellsInRow())
                .as("PANEL HEADING stayed in its own row -- the real header row is still only 3 cells")
                .isEqualTo(3);
        assertThat(evidence.maxPhysicalRowVerticalExtent()).isZero();
    }

    @Test
    void oneOutsizedRowDoesNotInflateTheAverageTheWayItInflatesTheMaximum() {
        // Four ordinary 2-cell rows plus one 6-cell outlier -- the exact distribution shape a
        // maximum alone cannot distinguish from "every row runs this large". The average (2.8) stays
        // close to the ordinary rows' own size, while the maximum (6) reports the outlier -- reading
        // both together is what tells the two distributions apart.
        List<PositionedText> positioned = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            float y = 100f + i * 20f;
            positioned.add(run("01/0" + (i + 1) + "/2026", 30f, 55f, y));
            positioned.add(run("500.00", 300f, 55f, y));
        }
        float outlierY = 300f;
        for (int i = 0; i < 6; i++) {
            positioned.add(run("FIELD" + i, 30f + i * 60f, 50f, outlierY));
        }

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        PdfTableLocator.PhysicalRowFormationEvidence evidence = doc.physicalRowFormationEvidence();
        assertThat(evidence.physicalRowsCreated()).isEqualTo(5);
        assertThat(evidence.maxCellsInRow()).isEqualTo(6);
        assertThat(evidence.totalPhysicalCells()).isEqualTo(14);
        assertThat(evidence.averageCellsPerRow())
                .as("the average stays near the ordinary rows' own size (2) rather than near the "
                        + "outlier's (6) -- exactly the context a bare maximum cannot provide")
                .isEqualTo(2.8, within(0.001));
        assertThat(evidence.cellCountDistribution())
                .as("the distribution is the most direct reading of the same fact: size 2 recurs "
                        + "four times, size 6 appears exactly once")
                .isEqualTo(Map.of(2, 4, 6, 1));
    }

    @Test
    void anEmptyDocumentHasNoRowsAndNoExtent() {
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(List.of(), ctx);

        PdfTableLocator.PhysicalRowFormationEvidence evidence = doc.physicalRowFormationEvidence();
        assertThat(evidence.textRuns()).isZero();
        assertThat(evidence.physicalRowsCreated()).isZero();
        assertThat(evidence.totalPhysicalCells()).isZero();
        assertThat(evidence.averageCellsPerRow()).isZero();
        assertThat(evidence.maxCellsInRow()).isZero();
        assertThat(evidence.maxPhysicalRowVerticalExtent()).isZero();
        assertThat(evidence.cellCountDistribution()).isEmpty();
    }
}
