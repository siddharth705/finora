package com.finora.imports.pdf.fixtures;

import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ExpectedEntity;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Presence;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.Row;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition.ZeroTransactions;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OCR-2C: can we reliably produce a genuinely scanned input?
 *
 * <p>Three properties, and the first is the one that makes the rest worth having. A fixture that
 * still carried a text layer would let a recogniser's tests pass while native extraction quietly
 * supplied the answer — so "no extractable text" is asserted, not assumed. The other two make the
 * artefact usable as a fixture at all: the same declaration must produce the same pixels, and the
 * same bytes.
 */
class ScannedPdfFixtureTest {

    private static SyntheticStatementDefinition declaration() {
        return new SyntheticStatementDefinition("synthetic-scanned-001", List.of(
                new ExpectedEntity("savings-primary", "SAVINGS", Presence.DETECTED, null,
                        ZeroTransactions.FALSE, List.of(
                                new Row(LocalDate.of(2026, 6, 5), "SALARY CREDIT",
                                        new BigDecimal("55000.00"), true),
                                new Row(LocalDate.of(2026, 6, 10), "GROCERY STORE",
                                        new BigDecimal("2000.00"), false)))),
                List.of());
    }

    @Test
    void theScannedDocumentHasNoExtractableText() throws Exception {
        byte[] scanned = PdfFixtureBuilder.renderScanned(declaration());

        assertThat(new PdfTextExtractor().extract(scanned))
                .as("a fixture that still carries a text layer proves nothing about recognition")
                .isEmpty();
    }

    @Test
    void theNativeDocumentDoesHaveText_soTheScanIsNotTriviallyEmpty() throws Exception {
        // The control. Without it, an empty scan and an empty SOURCE are indistinguishable, and the
        // assertion above would pass just as loudly if the renderer had produced nothing at all.
        assertThat(new PdfTextExtractor().extract(PdfFixtureBuilder.render(declaration())))
                .as("the source really does contain text, so its absence after scanning is the scan")
                .isNotEmpty();
    }

    @Test
    void theSameDeclarationProducesTheSamePixels() throws Exception {
        byte[] native_ = PdfFixtureBuilder.render(declaration());

        try (PDDocument document = Loader.loadPDF(native_)) {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage first = renderer.renderImageWithDPI(0, ScannedPdfFixture.DEFAULT_DPI);
            BufferedImage second = renderer.renderImageWithDPI(0, ScannedPdfFixture.DEFAULT_DPI);

            assertThat(second.getWidth()).isEqualTo(first.getWidth());
            assertThat(second.getHeight()).isEqualTo(first.getHeight());
            for (int y = 0; y < first.getHeight(); y++) {
                for (int x = 0; x < first.getWidth(); x++) {
                    if (first.getRGB(x, y) != second.getRGB(x, y)) {
                        throw new AssertionError("pixels differ at " + x + "," + y
                                + " -- rendering is not deterministic, and scanned fixtures cannot"
                                + " be reproducible if it is not");
                    }
                }
            }
        }
    }

    @Test
    void theSameDeclarationProducesByteIdenticalScannedPdfs() throws Exception {
        assertThat(PdfFixtureBuilder.renderScanned(declaration()))
                .as("pinned creation date, modification date, producer and document ID")
                .isEqualTo(PdfFixtureBuilder.renderScanned(declaration()));
    }

    @Test
    void theContainerMetadataIsPinnedRatherThanStamped() throws Exception {
        try (PDDocument document = Loader.loadPDF(PdfFixtureBuilder.renderScanned(declaration()))) {
            var info = document.getDocumentInformation();

            assertThat(info.getProducer()).isEqualTo("finora-synthetic-scan");
            assertThat(info.getCreationDate().getTimeInMillis()).isZero();
            assertThat(info.getModificationDate().getTimeInMillis()).isZero();
            assertThat(document.getDocument().getDocumentID()).isNotNull();
        }
    }

    @Test
    void theScannedDocumentKeepsItsPageGeometry() throws Exception {
        // Coordinates in the scanned document stay comparable to the native one's, so a recogniser
        // reporting a region can later be judged against layout ground truth with no scale factor.
        try (PDDocument nativeDoc = Loader.loadPDF(PdfFixtureBuilder.render(declaration()));
             PDDocument scanned = Loader.loadPDF(PdfFixtureBuilder.renderScanned(declaration()))) {

            assertThat(scanned.getNumberOfPages()).isEqualTo(nativeDoc.getNumberOfPages());
            assertThat(scanned.getPage(0).getMediaBox().getWidth())
                    .isEqualTo(nativeDoc.getPage(0).getMediaBox().getWidth());
            assertThat(scanned.getPage(0).getMediaBox().getHeight())
                    .isEqualTo(nativeDoc.getPage(0).getMediaBox().getHeight());
        }
    }
}
