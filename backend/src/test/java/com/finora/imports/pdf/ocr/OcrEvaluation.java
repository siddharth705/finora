package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.PdfTextExtractor;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import com.finora.imports.pdf.fixtures.SyntheticStatementDefinition;

import java.io.IOException;
import java.util.List;

/**
 * Runs a candidate recogniser through the WHOLE existing parser and reports what came out.
 *
 * <h2>Why the whole parser</h2>
 *
 * The tempting evaluation is to compare an engine's characters against the expected characters and
 * call the closer one better. That measures transcription, and transcription is not the thing being
 * bought. An engine that reads every glyph correctly but reports positions half a column off will
 * score beautifully on text and produce a ledger with amounts in the wrong rows. The question worth
 * answering is not "did it read the characters" but "does the money come out right", and only the
 * real parser can answer it.
 *
 * <p>So the engine's output is adapted to {@link PositionedText} and pushed through
 * {@link com.finora.imports.pdf.PdfTableLocator}, the normaliser and the verification rules
 * unchanged. A scorecard row is a statement about the pipeline, not about the engine in isolation.
 *
 * <h2>How it substitutes without touching production</h2>
 *
 * {@link PdfTextExtractor#extract} is overridden to return the recognised runs. Production is not
 * modified, no acquirer is wired in, and no routing decision is made -- OCR-3A chooses an engine and
 * nothing else. When routing does arrive it will go through
 * {@link com.finora.imports.pdf.acquisition.DocumentTextAcquirer}, and this class will not be how it
 * gets there.
 *
 * <h2>The evaluation's own honesty</h2>
 *
 * Documents are RENDERED from a declaration and then rasterised, so the expected values descend
 * from the declaration and never from any reading of the image. No real statement is involved at
 * any point, which is what allows the observation to carry financial values at all.
 */
public final class OcrEvaluation {

    private OcrEvaluation() {}

    /**
     * The resolution OCR is evaluated at, and the one the production acquirer actually uses.
     *
     * <p>Not a preference. Swept over ten statement layouts at both resolutions, 150 DPI never
     * exceeds seven of ten on ledger equivalence at ANY assembly threshold, while 300 DPI reaches
     * nine or ten across a broad band. The three layouts that fail at 150 fail for every threshold
     * tried, so the limit is the pixels rather than the grouping -- 9pt text at 150 DPI is about
     * nineteen pixels tall, and the characters that decide a financial value are the ones that go
     * first.
     *
     * <p>{@link ScannedPdfFixture#DEFAULT_DPI} stays at 150 deliberately: it describes what a
     * scanner produces, which is the input OCR has to cope with, not the resolution OCR should
     * rasterise at. The two numbers answer different questions and should not be shared.
     *
     * <p>Delegates to {@link TesseractRecogniser#OCR_DPI} rather than declaring its own value, now
     * that class is the one actually rasterising in production -- one measured number, not two
     * copies of it that could quietly drift apart.
     */
    public static final int OCR_DPI = TesseractRecogniser.OCR_DPI;

    /** Everything one engine produced for one document, including what it could not report. */
    public record Observation(String engine, String json, int runsRecognised, Float meanConfidence,
                              List<PositionedText> recognised) {}

    /**
     * Render the declaration, rasterise it, recognise it, parse it.
     *
     * @param dpi the resolution to rasterise at -- part of the evaluation, since an engine that
     *            needs 300 DPI to match another's 150 is buying accuracy with time and that trade
     *            belongs on the scorecard rather than hidden inside a default.
     */
    public static Observation run(OcrEngine engine, SyntheticStatementDefinition definition, int dpi)
            throws Exception {
        byte[] scanned = ScannedPdfFixture.scan(PdfFixtureBuilder.render(definition), dpi);
        List<OcrEngine.RecognisedText> recognised = engine.recognise(scanned, dpi);
        List<PositionedText> runs = RecognisedTextAdapter.toPositionedText(recognised);

        return new Observation(engine.name(), OcrProbe.probe(engine.name(), runs),
                runs.size(), meanConfidence(recognised), runs);
    }

    /**
     * Null when no run carried a confidence, rather than zero or one.
     *
     * <p>"This engine does not tell you how sure it is" is a real property of an engine and a real
     * cost -- confidence is what any later routing decision would have to be built on. Averaging
     * nulls into a number would make an engine that reports nothing look identical to one that
     * reports perfect certainty.
     */
    private static Float meanConfidence(List<OcrEngine.RecognisedText> recognised) {
        var reported = recognised.stream().map(OcrEngine.RecognisedText::confidence)
                .filter(java.util.Objects::nonNull).toList();
        if (reported.isEmpty()) return null;
        double total = reported.stream().mapToDouble(Float::doubleValue).sum();
        return (float) (total / reported.size());
    }

    /** The runs a perfect recogniser would have to produce, for calibrating the harness itself. */
    static List<PositionedText> nativeRunsOf(byte[] pdf) throws IOException {
        return new PdfTextExtractor().extract(pdf);
    }
}
