package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.acquisition.AcquiredDocument;
import com.finora.imports.pdf.acquisition.RecognisingTextAcquirer;

import java.io.IOException;

/**
 * Tesseract as a routable acquirer: recognise, assemble, hand back runs.
 *
 * <h2>Why this is in test scope</h2>
 *
 * An OCR engine is an operational dependency of a deployment, not a library. Shipping this as a
 * {@code @Component} would make every environment that lacks the binary fail at a point where it
 * currently gives a clear message, and would commit the project to installing Tesseract in the
 * production image -- a deployment decision with cost, image-size and support consequences that is
 * not this change's to make.
 *
 * <p>So routing ships, and the engine that plugs into it is exercised here. That is deliberate
 * rather than a compromise: {@code RoutingTextAcquirer}'s own tests run with NO recogniser, which
 * is the configuration production actually runs, and this class proves the same seam carries a real
 * engine end to end when one is installed.
 *
 * <p>Everything it does is the two things OCR-3A and OCR-3B measured -- rasterise at
 * {@link OcrEvaluation#OCR_DPI}, and assemble word-level runs into phrases. Neither number is
 * chosen here; both are recorded where they were measured.
 */
public final class TesseractRecogniser implements RecognisingTextAcquirer {

    private final TesseractEngine engine = new TesseractEngine();

    @Override
    public AcquiredDocument acquire(byte[] fileBytes, String password) throws IOException {
        var recognised = RunAssembler.assemble(engine.recognise(fileBytes, OcrEvaluation.OCR_DPI));
        return AcquiredDocument.of(RecognisedTextAdapter.toPositionedText(recognised));
    }

    /**
     * Whether the engine is installed at all.
     *
     * <p>Capability, honestly reported: an engine that is not present cannot attempt anything, and
     * saying so here means routing skips it rather than catching an exception from it. The password
     * is not consulted because Tesseract reads pixels -- an encrypted PDF that PDFBox could not open
     * will not have rendered, and that failure belongs to rendering.
     */
    @Override
    public boolean supports(byte[] fileBytes) {
        return TesseractEngine.available();
    }
}
