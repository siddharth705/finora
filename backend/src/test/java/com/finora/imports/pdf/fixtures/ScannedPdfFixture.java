package com.finora.imports.pdf.fixtures;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Turns a PDF into a genuinely scanned one: every page rendered to an image, and nothing else.
 *
 * <h2>Why "genuinely"</h2>
 *
 * A fixture that still carried a text layer would let a recogniser's tests pass while proving
 * nothing -- native extraction would quietly supply the answer OCR was supposed to produce. So the
 * output contains images and no text operators at all, and that is asserted rather than assumed:
 * {@code PdfTextExtractor} must recover ZERO runs from it.
 *
 * <h2>Determinism, and the four fields that stood in its way</h2>
 *
 * Rendering itself is deterministic -- the same page produces pixel-identical output run after run,
 * which was measured before this class was written. What was not reproducible was the container:
 * PDFBox stamps a creation date, a modification date, a producer and a random document ID on every
 * save, so two byte streams differed while their pixels were identical.
 *
 * <p>Those four are pinned, and the distinction mattered. Had the PIXELS varied, scanned fixtures
 * would have been fundamentally unreproducible and this milestone would need a different approach;
 * a varying container is a four-line fix. Diagnosing which one it was is the only reason this class
 * exists in its current form.
 *
 * <h2>Nothing is written anywhere</h2>
 *
 * Source and result are both {@code byte[]}. No temporary file, no path, no cleanup to forget --
 * a generated artefact that never reaches a filesystem cannot survive a failed test.
 */
public final class ScannedPdfFixture {

    /**
     * Enough to read a 9pt statement, cheap enough to render in a test. Not a claim about what a
     * recogniser needs: engine evaluation will measure that, and this is the input it measures on.
     */
    public static final int DEFAULT_DPI = 150;

    /** A fixed instant, so a saved document says the same thing on every run and on every machine. */
    private static final long PINNED_EPOCH_MILLIS = 0L;
    private static final String PINNED_PRODUCER = "finora-synthetic-scan";
    private static final String PINNED_DOCUMENT_ID = "finora-synthetic-scan";

    private ScannedPdfFixture() {}

    public static byte[] scan(byte[] source) throws IOException {
        return scan(source, DEFAULT_DPI);
    }

    public static byte[] scan(byte[] source, int dpi) throws IOException {
        try (PDDocument in = Loader.loadPDF(source); PDDocument out = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(in);
            for (int page = 0; page < in.getNumberOfPages(); page++) {
                // The page keeps its original size, so coordinates in the scanned document are
                // comparable to the native one's. A recogniser reporting a region can then be
                // judged against layout ground truth without a scale factor in between.
                PDRectangle size = in.getPage(page).getMediaBox();
                BufferedImage image = renderer.renderImageWithDPI(page, dpi);

                PDPage imageOnly = new PDPage(size);
                out.addPage(imageOnly);
                PDImageXObject drawn = LosslessFactory.createFromImage(out, image);
                try (PDPageContentStream content = new PDPageContentStream(out, imageOnly)) {
                    content.drawImage(drawn, 0, 0, size.getWidth(), size.getHeight());
                }
            }
            pinContainerMetadata(out);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return bytes.toByteArray();
        }
    }

    /**
     * The four values PDFBox would otherwise vary per save. Without these the same definition
     * produces the same pixels inside a different byte stream, which is reproducible enough to look
     * fine and not reproducible enough to compare.
     */
    private static void pinContainerMetadata(PDDocument document) {
        Calendar fixed = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        fixed.setTimeInMillis(PINNED_EPOCH_MILLIS);

        var information = document.getDocumentInformation();
        information.setCreationDate(fixed);
        information.setModificationDate(fixed);
        information.setProducer(PINNED_PRODUCER);

        COSArray id = new COSArray();
        id.add(new COSString(PINNED_DOCUMENT_ID));
        id.add(new COSString(PINNED_DOCUMENT_ID));
        document.getDocument().setDocumentID(id);
    }
}
