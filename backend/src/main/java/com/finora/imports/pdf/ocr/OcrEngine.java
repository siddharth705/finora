package com.finora.imports.pdf.ocr;

import java.io.IOException;
import java.util.List;

/**
 * A candidate character recogniser, as the evaluation sees it.
 *
 * <h2>Evaluation scope, originally</h2>
 *
 * OCR-3A defined this in test sources deliberately: the point was to decide WHICH engine, and an
 * interface production already used would have pre-committed to the answer. It moved to main
 * scope only once that decision was made (see {@code TesseractRecogniser}) — the contract itself
 * is unchanged, and {@code OcrScorecardEmitter}'s comparison of candidates still lives in test
 * sources against this same interface.
 *
 * <p>Kept minimal on purpose: a candidate is asked for text with positions and confidence, and
 * nothing else. Anything richer would encode one engine's model of a document into the contract
 * used to compare it against another.
 */
public interface OcrEngine {

    /** A name for the scorecard. */
    String name();

    /**
     * @param pdf   an image-only PDF -- see ScannedPdfFixture
     * @param dpi   the resolution the images were rendered at, which a recogniser needs in order to
     *              report positions in PDF points rather than pixels
     */
    List<RecognisedText> recognise(byte[] pdf, int dpi) throws IOException;

    /**
     * One recognised run, in PDF user space.
     *
     * <p>Positions are in POINTS, not pixels, and converting is the adapter's job rather than the
     * comparison's: an engine that reports pixels at 150 DPI and one that reports points would
     * otherwise score differently for being differently calibrated, which says nothing about
     * whether either read the money correctly.
     *
     * @param confidence 0..1, or null when the engine does not report one. Null is a real answer --
     *                   "this engine cannot tell you" is a finding about the engine, and coercing it
     *                   to 1.0 would hide exactly the property the evaluation is meant to measure.
     */
    record RecognisedText(String text, float x, float y, float width, float height,
                           int pageIndex, Float confidence) {}
}
