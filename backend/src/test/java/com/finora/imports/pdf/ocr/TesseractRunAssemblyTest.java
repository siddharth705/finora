package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Assembly across every statement layout the repository already has, not just the one it was
 * measured on.
 *
 * <h2>The question</h2>
 *
 * {@link RunAssembler}'s threshold was derived from one fixture's gap distribution. A threshold that
 * only works on the document it was measured on is a constant with a story attached. So this asks
 * the general form: for a statement whose native extraction the parser already handles correctly,
 * does the SAME statement rasterised, recognised and assembled produce the SAME ledger?
 *
 * <p>Comparing OCR against native extraction rather than against a declaration is deliberate. These
 * fixtures have no value-level ground truth -- they are the parser's own regression corpus -- but
 * they do have a known-good reading, and equivalence to it is exactly the claim assembly makes.
 * Where the two disagree the fixture is reported, since a layout assembly cannot yet handle is a
 * finding rather than a reason to loosen the threshold.
 *
 * <h2>Skipped without the binary</h2>
 *
 * Tesseract is a developer's local install, not a build dependency, so CI has none and these skip
 * there. Nothing else in the suite depends on an engine -- the harness's own calibration runs on
 * stubs precisely so that it does not.
 */
class TesseractRunAssemblyTest {

    @BeforeEach
    void requireTesseract() {
        assumeTrue(TesseractEngine.available(), "tesseract is not installed");
    }

    /**
     * Layouts chosen for the ways they can break assembly, not for coverage's sake: columns whose
     * anchors are offset, a dense multi-column grid, descriptions that wrap, a schedule whose header
     * wraps onto three lines, and amounts carrying Dr/Cr suffixes that a word-level engine emits as
     * separate tokens.
     */
    private static List<Fixture> layouts() {
        return List.of(
                new Fixture("offset column anchors", PdfFixtureBuilder::buildOffsetColumnAnchorsSample),
                new Fixture("multi-column payment grid", PdfFixtureBuilder::buildMultiColumnPaymentSummaryGridSample),
                new Fixture("wrapped description", PdfFixtureBuilder::buildWrappedDescriptionCreditCardSample),
                new Fixture("wrapped header schedule", PdfFixtureBuilder::buildWrappedHeaderDepositScheduleSample),
                new Fixture("singular deposit/withdrawal", PdfFixtureBuilder::buildSingularDepositWithdrawalColumnsSample),
                new Fixture("reference number and balance", PdfFixtureBuilder::buildReferenceNumberAndBalanceSample),
                new Fixture("leading narration continuation", PdfFixtureBuilder::buildLeadingNarrationContinuationSample));
    }

    private record Fixture(String name, ThrowingSupplier build) {}

    private interface ThrowingSupplier {
        byte[] get() throws Exception;
    }

    /**
     * The ledger from assembled OCR must equal the ledger from native extraction.
     *
     * <p>Compared on the observation record -- dates, amounts, directions and row counts -- rather
     * than on the runs, because matching runs is not the claim. Two segmentations may differ and
     * still produce the same money, and it is the money that has to survive.
     */
    @Test
    void assembledRecognitionReadsTheSameLedgerAsNativeExtraction() throws Exception {
        StringBuilder differences = new StringBuilder();

        for (Fixture fixture : layouts()) {
            byte[] source = fixture.build().get();
            String expected = OcrProbe.probe("native", OcrEvaluation.nativeRunsOf(source));

            if (!expected.equals(readAssembled(source))) {
                differences.append("\n  ").append(fixture.name())
                        .append("\n    native: ").append(ledger(expected))
                        .append("\n    ocr:    ").append(ledger(readAssembled(source)));
            }
        }

        assertThat(differences.toString())
                .as("assembled OCR must read the same ledger as native extraction on layouts the "
                        + "parser already handles")
                .isEmpty();
    }

    /**
     * The eighth layout, pinned as it actually behaves rather than left out of the list.
     *
     * <p>Its amounts print as {@code '37.94 Dr'}, which PDFBox emits as one run and a word-level
     * engine emits as two. Whether they rejoin depends on a gap that is not reliably smaller than
     * the gap to the next column, so no value of {@link RunAssembler#JOIN_WITHIN} fixes this one
     * without breaking others -- 0.58x-0.64x buys it back and costs the stability the sweep in
     * RunAssembler records.
     *
     * <p>It is not bought back with vocabulary either. Teaching the assembler that {@code Dr} and
     * {@code Cr} belong to the amount before them would put statement terminology inside a
     * geometric component, which is the boundary {@code PdfTableLocator} already refuses to cross.
     * A component that knows what a debit is cannot be reused for a document that does not have
     * any.
     *
     * <p>Asserted, so that a change which fixes it fails here and gets noticed rather than passing
     * quietly.
     */
    @Test
    void doesNotYetHandleAmountsWithDrCrSuffixes() throws Exception {
        byte[] source = PdfFixtureBuilder.buildDrCrSuffixAmountColumnSample();

        assertThat(ledger(OcrProbe.probe("native", OcrEvaluation.nativeRunsOf(source))))
                .as("natively this statement reads as five transactions")
                .contains("\"rows\":5");
        assertThat(ledger(readAssembled(source)))
                .as("through OCR it does not, and this pins that rather than hiding it")
                .doesNotContain("\"rows\":5");
    }

    /** The transactions only, so a reported difference is readable. */
    private static String ledger(String observation) {
        int from = observation.indexOf("\"sectionDetail\"");
        return from < 0 ? observation : observation.substring(from);
    }

    /** Rasterise, recognise, assemble, parse -- the whole OCR path for one document. */
    private static String readAssembled(byte[] source) throws Exception {
        byte[] scanned = ScannedPdfFixture.scan(source, 300);
        var assembled = RunAssembler.assemble(new TesseractEngine().recognise(scanned, 300));
        return OcrProbe.probe("native", RecognisedTextAdapter.toPositionedText(assembled));
    }

    /**
     * Assembly must not merge across a column boundary, stated directly rather than inferred from a
     * ledger that happened to survive.
     *
     * <p>Uses measured geometry: on the evaluation fixture the description column ends around x=238
     * and the value column begins at x=301, a gap of roughly 63pt against a median run height near
     * 6.5pt. Nothing here is invented -- the numbers are the ones the gap distribution reported.
     */
    @Test
    void doesNotJoinAcrossAColumnGap() {
        var runs = List.of(
                new OcrEngine.RecognisedText("TRANSACTION", 163.2f, 255.6f, 63.6f, 6.5f, 0, 0.96f),
                new OcrEngine.RecognisedText("11", 230.9f, 255.6f, 7.4f, 6.5f, 0, 0.96f),
                new OcrEngine.RecognisedText("11.00", 301.0f, 255.6f, 21.1f, 6.5f, 0, 0.96f));

        var assembled = RunAssembler.assemble(runs);

        assertThat(assembled).hasSize(2);
        assertThat(assembled.get(0).text())
                .as("the description keeps its own trailing number")
                .isEqualTo("TRANSACTION 11");
        assertThat(assembled.get(1).text())
                .as("and the amount stays a separate run -- joining these produced 111.00")
                .isEqualTo("11.00");
    }

    /** Runs on different lines are never joined, however close horizontally. */
    @Test
    void doesNotJoinAcrossLines() {
        var runs = List.of(
                new OcrEngine.RecognisedText("SALARY", 130f, 121f, 30f, 6.5f, 0, 0.96f),
                new OcrEngine.RecognisedText("GROCERY", 130f, 131f, 35f, 6.5f, 0, 0.96f));

        assertThat(RunAssembler.assemble(runs)).hasSize(2);
    }

    /** A merged phrase is only as certain as its least certain word. */
    @Test
    void confidenceOfAPhraseIsItsWeakestWord() {
        var runs = List.of(
                new OcrEngine.RecognisedText("SALARY", 130f, 121f, 30f, 6.5f, 0, 0.99f),
                new OcrEngine.RecognisedText("CREDIT", 163f, 121f, 30f, 6.5f, 0, 0.42f));

        var assembled = RunAssembler.assemble(runs);

        assertThat(assembled).hasSize(1);
        assertThat(assembled.get(0).confidence())
                .as("averaging would hide a shaky word inside a confident phrase")
                .isEqualTo(0.42f);
    }

    /** An engine reporting no confidence still reports none after assembly. */
    @Test
    void assemblyDoesNotInventConfidence() {
        var runs = List.of(
                new OcrEngine.RecognisedText("SALARY", 130f, 121f, 30f, 6.5f, 0, null),
                new OcrEngine.RecognisedText("CREDIT", 163f, 121f, 30f, 6.5f, 0, null));

        assertThat(RunAssembler.assemble(runs).get(0).confidence()).isNull();
    }
}
