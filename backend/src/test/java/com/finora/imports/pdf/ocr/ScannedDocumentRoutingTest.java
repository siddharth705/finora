package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.acquisition.NativePdfAcquirer;
import com.finora.imports.pdf.acquisition.RoutingTextAcquirer;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import com.finora.imports.pdf.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The product outcome: a scanned statement imports.
 *
 * <p>Everything before this milestone measured pieces -- an engine's reading, a threshold, an
 * assembly rule. This asserts the thing a user would notice: the same PDF that produces "this PDF
 * has no text in it" with no engine deployed produces a ledger with one deployed, through the real
 * pipeline, with routing making the choice.
 */
class ScannedDocumentRoutingTest {

    @BeforeEach
    void requireTesseract() {
        assumeTrue(TesseractEngine.available(), "tesseract is not installed");
    }

    private static com.finora.imports.pdf.PdfPreviewGenerator generator(boolean withEngine) {
        var routing = new RoutingTextAcquirer(new NativePdfAcquirer(new PdfTextExtractor()),
                withEngine ? List.of(new TesseractRecogniser()) : List.of());
        return OcrProbe.generatorFor(routing);
    }

    /** With no engine deployed, a scanned statement yields nothing -- today's behaviour. */
    @Test
    void withoutAnEngineAScannedStatementStillYieldsNoTransactions() throws Exception {
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.buildReferenceNumberAndBalanceSample());

        var generated = generator(false).generateSectionsWithContext(
                UUID.randomUUID(), "scanned.pdf", scanned, null);

        assertThat(generated.sections()).allSatisfy(s -> assertThat(s.rows()).isEmpty());
        assertThat(generated.documentContext().hasNoExtractableText())
                .as("and the context still says WHY, which is what ExtractionCheck reports on")
                .isTrue();
    }

    /**
     * With one deployed, the same bytes produce the same ledger the native PDF produces.
     *
     * <p>Compared against native rather than against a hand-written expectation: the claim routing
     * makes is that acquisition is interchangeable, and the statement's own text layer is the only
     * standard that can hold it to that.
     */
    @Test
    void withAnEngineTheSameScannedStatementReadsLikeTheNativeOne() throws Exception {
        byte[] source = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();
        byte[] scanned = ScannedPdfFixture.scan(source);

        var fromNative = generator(false).generateSectionsWithContext(
                UUID.randomUUID(), "native.pdf", source, null);
        var fromScan = generator(true).generateSectionsWithContext(
                UUID.randomUUID(), "scanned.pdf", scanned, null);

        assertThat(rows(fromScan)).isNotEmpty();
        assertThat(rows(fromScan))
                .as("a scanned statement and its native original must reach the same ledger")
                .isEqualTo(rows(fromNative));
    }

    /**
     * The end-to-end proof that OCR provenance reaches {@code reliabilityStatus}, not just a unit
     * test against synthetic inputs: routing -> {@code DocumentContext.recordTextSource} ->
     * {@code ImportVerifier} -> {@code ImportReliabilityStatusDeriver}, on a real acquired
     * document. A different fixture from the test above, deliberately: {@code
     * buildReferenceNumberAndBalanceSample}'s own balances don't reconcile (it exists to test
     * reference-number/balance-column layout, not financial consistency), so it reports
     * NEEDS_ATTENTION on FAILED findings regardless of acquisition path and can't isolate OCR's
     * own contribution. This fixture prints totals that DO reconcile, so native reads CLEAN and
     * only acquisition differs between the two runs.
     */
    @Test
    void ocrAcquisitionAloneAsksForReviewEvenWhenTheLedgerMatchesExactly() throws Exception {
        byte[] source = PdfFixtureBuilder.buildReconciledSummaryNoBalanceColumnSample();
        byte[] scanned = ScannedPdfFixture.scan(source);

        var fromNative = generator(false).generateSectionsWithContext(
                UUID.randomUUID(), "native.pdf", source, null);
        var fromScan = generator(true).generateSectionsWithContext(
                UUID.randomUUID(), "scanned.pdf", scanned, null);

        assertThat(rows(fromScan))
                .as("a scanned statement and its native original must reach the same ledger")
                .isNotEmpty()
                .isEqualTo(rows(fromNative));

        assertThat(fromNative.sections().get(0).verification().reliabilityStatus())
                .isEqualTo(com.finora.imports.ImportReliabilityStatus.CLEAN);
        assertThat(fromScan.sections().get(0).verification().reliabilityStatus())
                .as("OCR acquisition alone is enough to ask for review, even when the ledger matches exactly")
                .isEqualTo(com.finora.imports.ImportReliabilityStatus.REVIEW_RECOMMENDED);
    }

    /** And a native document is untouched by having an engine available. */
    @Test
    void deployingAnEngineDoesNotChangeANativeDocument() throws Exception {
        byte[] source = PdfFixtureBuilder.buildReferenceNumberAndBalanceSample();

        assertThat(rows(generator(true).generateSectionsWithContext(
                UUID.randomUUID(), "native.pdf", source, null)))
                .isEqualTo(rows(generator(false).generateSectionsWithContext(
                        UUID.randomUUID(), "native.pdf", source, null)));
    }

    private static String rows(com.finora.imports.pdf.PdfPreviewGenerator.PdfGenerationResult r) {
        return r.sections().stream()
                .flatMap(s -> s.rows().stream())
                .map(row -> row.date() + "|" + row.amount() + "|" + row.type() + "|" + row.description())
                .reduce("", (a, b) -> a + "\n" + b);
    }
}
