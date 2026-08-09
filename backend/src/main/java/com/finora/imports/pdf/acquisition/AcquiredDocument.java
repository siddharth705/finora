package com.finora.imports.pdf.acquisition;

import com.finora.imports.pdf.PositionedText;
import com.finora.imports.pdf.TextSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A document's text, and how it was obtained.
 *
 * <p>The runs are the same {@link PositionedText} the pipeline has always consumed, deliberately:
 * everything downstream of acquisition should be unable to tell whether the characters were read
 * from a text layer or recognised from pixels. Introducing a second document model for OCR would
 * mean two parsers, two sets of capabilities and two places for a defect to hide, which is the
 * outcome this seam exists to prevent.
 *
 * @param runs   every text run, each carrying its own {@link TextSource}
 * @param source how the DOCUMENT as a whole was acquired. Derived from the runs rather than
 *               declared, so it cannot drift from what they actually say.
 */
public record AcquiredDocument(List<PositionedText> runs, TextSource source) {

    /**
     * Provenance is a property of the runs, so it cannot be declared to be something else.
     *
     * <p>Deriving it in {@link #of} alone would leave the canonical constructor able to assert a
     * document was natively read when its runs say otherwise -- and a wrong provenance is worse
     * than none, because reconciliation would then trust characters that had in fact been inferred.
     * The constructor refuses rather than silently correcting: quietly overwriting the argument
     * would make a caller's mistaken belief invisible to them.
     */
    public AcquiredDocument {
        runs = List.copyOf(runs);
        TextSource derived = sourceOf(runs);
        if (source != derived) {
            throw new IllegalArgumentException(
                    "acquisition source is derived from the runs, not declared: these runs are "
                            + derived + " and cannot be recorded as " + source);
        }
    }

    public static AcquiredDocument of(List<PositionedText> runs) {
        return new AcquiredDocument(runs, sourceOf(runs));
    }

    /**
     * A document is NATIVE_PLUS_OCR when its runs disagree about where they came from -- which is
     * a real shape, not a defensive case: a cover page with a text layer above a scanned
     * transaction table produces exactly this, and is the reason acquisition is not modelled as a
     * document-wide either/or.
     *
     * <p>An empty document reports NATIVE_PDF rather than inventing a mixed origin for text that
     * does not exist. Nothing was recognised, so nothing here can be confidently wrong.
     */
    private static TextSource sourceOf(List<PositionedText> runs) {
        Set<TextSource> sources = runs.stream().map(PositionedText::source).collect(Collectors.toSet());
        if (sources.size() > 1) return TextSource.NATIVE_PLUS_OCR;
        return sources.stream().findFirst().orElse(TextSource.NATIVE_PDF);
    }

    /** Runs whose characters were inferred rather than read -- the subset a reconciliation step
     *  has any reason to treat with suspicion. */
    public List<PositionedText> recognisedRuns() {
        return runs.stream().filter(PositionedText::isRecognised).toList();
    }
}
