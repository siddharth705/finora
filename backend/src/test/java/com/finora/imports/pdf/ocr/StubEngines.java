package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.PositionedText;

import java.io.IOException;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Recognisers that do not recognise anything, for proving the harness before any engine exists.
 *
 * <h2>What these are for</h2>
 *
 * A scorecard is only worth reading if the thing producing it can fail. Installing PaddleOCR and
 * Tesseract and watching numbers appear would tell us very little: if the harness scored every
 * engine 100% because the comparison was hollow, the output would look exactly the same. These stubs
 * are the calibration -- a known-perfect input must score perfectly and a known-broken one must not,
 * and both must be demonstrated with no engine installed at all.
 *
 * <h2>What these are NOT</h2>
 *
 * None of these is a candidate. {@link #ceiling} in particular reads the source document's text
 * layer, which is not recognition and is not available for a real scan; it exists to establish what
 * a flawless engine would score, so that a real engine's shortfall can be attributed to the engine
 * rather than to the fixture or the parser. A scorecard listing it beside PaddleOCR would be
 * comparing an engine against a copy of the answer.
 */
final class StubEngines {

    private StubEngines() {}

    /**
     * Perfect recognition: the source document's own text layer, positions and all.
     *
     * @param confidence what to report per run, or null to report none -- both are real engine
     *                   behaviours and the harness must handle each without inventing a number.
     */
    static OcrEngine ceiling(byte[] sourcePdf, Float confidence) {
        return named("stub:ceiling", sourcePdf, confidence, UnaryOperator.identity());
    }

    /**
     * Perfect except for one digit in one amount -- the canonical financial misread.
     *
     * <p>55,000.00 recognised as 35,000.00: the right number of transactions, the right dates, the
     * right directions, and one wrong number. Character-level accuracy barely moves. A harness that
     * cannot fail this cannot protect a ledger, which is why it is the negative case the evaluation
     * is calibrated against.
     */
    static OcrEngine misreadsOneAmount(byte[] sourcePdf) {
        return named("stub:misread-amount", sourcePdf, 0.98f,
                run -> run.replace("55,000.00", "35,000.00").replace("55000.00", "35000.00"));
    }

    /**
     * Every character correct, and one amount landing under the neighbouring heading.
     *
     * <p>The failure mode a transcription score is blind to, and the one that matters most. In the
     * fixture the deposit column sits at x=380 and the withdrawal column at x=300; a recogniser
     * whose value positions drift 80pt left reads every digit of 55,000.00 correctly and files it as
     * money leaving the account. That is the original HDFC bug reproduced from geometry alone --
     * character accuracy 100%, ledger inverted.
     *
     * <p>Only the value runs drift, not the headings. That asymmetry is the realistic one: headings
     * are short, well-separated, high-contrast text, and it is the dense digit rows that move. A
     * UNIFORM shift of every run was tried first and correctly changed nothing, because the locator
     * reads columns relative to one another -- a stub that moves the whole page tests translation
     * invariance, not misfiling.
     *
     * @param fromX runs at or beyond this x drift; those before it stay put
     */
    static OcrEngine driftsValueColumn(byte[] sourcePdf, float fromX, float points) {
        return new OcrEngine() {
            @Override public String name() { return "stub:drifted-value-column"; }
            @Override public List<RecognisedText> recognise(byte[] pdf, int dpi) throws IOException {
                return OcrEvaluation.nativeRunsOf(sourcePdf).stream()
                        .map(p -> new RecognisedText(p.text(),
                                p.x() >= fromX && isValue(p.text()) ? p.x() - points : p.x(),
                                p.y(), p.width(), p.height(), p.pageIndex(), 0.99f))
                        .toList();
            }
        };
    }

    /** A run the drift applies to: digits and separators, i.e. what sits in a money column. */
    private static boolean isValue(String text) {
        return text != null && text.matches("[0-9][0-9,.]*");
    }

    /** Recognises nothing at all, as a failed or unsupported engine would. */
    static OcrEngine blind() {
        return new OcrEngine() {
            @Override public String name() { return "stub:blind"; }
            @Override public List<RecognisedText> recognise(byte[] pdf, int dpi) { return List.of(); }
        };
    }

    private static OcrEngine named(String name, byte[] sourcePdf, Float confidence,
                                    UnaryOperator<String> transcribe) {
        return new OcrEngine() {
            @Override public String name() { return name; }
            @Override public List<RecognisedText> recognise(byte[] pdf, int dpi) throws IOException {
                return OcrEvaluation.nativeRunsOf(sourcePdf).stream().map(StubEngines::asRun)
                        .map(r -> new RecognisedText(transcribe.apply(r.text()), r.x(), r.y(),
                                r.width(), r.height(), r.pageIndex(), confidence))
                        .toList();
            }
        };
    }

    private static OcrEngine.RecognisedText asRun(PositionedText p) {
        return new OcrEngine.RecognisedText(p.text(), p.x(), p.y(), p.width(), p.height(),
                p.pageIndex(), null);
    }
}
