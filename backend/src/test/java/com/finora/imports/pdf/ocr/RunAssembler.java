package com.finora.imports.pdf.ocr;

import com.finora.imports.pdf.ocr.OcrEngine.RecognisedText;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups an engine's word-level output into the phrase-level runs the parser was built to read.
 *
 * <h2>The defect this exists for</h2>
 *
 * PDFBox emits one run per text-show operation, which for a statement is normally one per table
 * cell: {@code 'FILLER TRANSACTION 10'} arrives as a single run 110pt wide. OCR engines emit one run
 * per word. Finora's parser was built against the first, so the trailing token of a description
 * lands near the value columns as a bare number and is absorbed into the amount -- measured on
 * Tesseract 5.5.3, where {@code 11.00} became {@code 111.00} while every character was recognised
 * correctly.
 *
 * <p>Word-level output is the norm rather than a Tesseract quirk, so this is a property of the
 * boundary between any recogniser and this parser, and it belongs here rather than inside an engine.
 *
 * <h2>Why the threshold is relative to text height</h2>
 *
 * Absolute gaps move with resolution and font size; their ratio to the text height barely does.
 * Measured on the evaluation fixture, a gap inside a phrase is about 0.5x the median run height at
 * both 150 and 300 DPI, while the columns there are 5x apart. A point constant would have been
 * silently wrong at the first statement set in 7pt.
 *
 * <h2>What this cannot do</h2>
 *
 * Reproduce PDFBox's segmentation exactly. PDFBox emits one run per text-show operation, which is a
 * fact about how the PDF was authored rather than about how it looks -- one show can contain forty
 * spaces, and {@code 'FUEL SURCHARGE          10.00 Dr'} arrives as a single 202pt run whose
 * internal gap is 12.9x the text height. Measured across eight layouts, gaps inside one native run
 * reach 12.86x while gaps between separate native runs start below zero, so the two populations
 * overlap and no threshold separates them. Assembly gets the ledger right on seven of eight; the
 * eighth is documented rather than tuned away.
 *
 */
public final class RunAssembler {

    /**
     * Gap at which two runs stop being one phrase, as a multiple of the document's median run
     * height.
     *
     * <p>Chosen for stability rather than for score. Swept against ledger equivalence over ten
     * statement layouts at both resolutions:
     *
     * <pre>
     *   threshold    150 DPI   300 DPI
     *   0.55x         4/10      8/10
     *   0.58x         7/10      9/10
     *   0.64x         7/10     10/10
     *   0.70x-1.10x   7/10      9/10      &lt;- broad plateau
     *   1.25x         5/10      8/10
     *   1.50x         5/10      8/10
     * </pre>
     *
     * Ten of ten is reachable at 0.64x and only there; a constant that has to land on one point to
     * work against ten documents is a fit to those ten, and the first statement in a different font
     * moves the point out from under it. 0.85x sits in the middle of the plateau instead, where a
     * quarter either way changes nothing.
     *
     * <p>An estimate derived from the document's own space width -- the median of each line's
     * smallest gap -- was tried in place of this and behaved no better, so the simpler measure
     * stayed.
     *
     * <p>Note what the same sweep says about resolution: 150 DPI never exceeds 7/10 at ANY
     * threshold. Three layouts fail there whatever this number is, which makes them a resolution
     * limit rather than an assembly one. See {@code OcrEvaluation#OCR_DPI}.
     *
     * <p>The layout this plateau costs is recorded in {@code TesseractRunAssemblyTest}, along with
     * the reason it cannot be bought back by moving this number.
     */
    static final float JOIN_WITHIN = 0.85f;

    /** Fraction of the shorter run's height that two runs must share vertically to be one line. */
    private static final float SAME_LINE_OVERLAP = 0.5f;

    private RunAssembler() {}

    public static List<RecognisedText> assemble(List<RecognisedText> runs) {
        if (runs.isEmpty()) return runs;

        Map<Integer, List<RecognisedText>> byPage = new LinkedHashMap<>();
        for (RecognisedText r : runs) byPage.computeIfAbsent(r.pageIndex(), p -> new ArrayList<>()).add(r);
        List<List<RecognisedText>> allLines = new ArrayList<>();
        for (List<RecognisedText> page : byPage.values()) allLines.addAll(lines(page));

        float join = JOIN_WITHIN * medianHeight(runs);

        List<RecognisedText> assembled = new ArrayList<>();
        for (List<RecognisedText> line : allLines) assembled.addAll(joinAlong(line, join));
        return assembled;
    }

    /**
     * Lines by vertical overlap rather than by rounding y to a bucket.
     *
     * <p>Rounding was tried first and split the same document into 74 lines at one resolution and 81
     * at another -- a line grouping that changes with DPI would move the assembly's answer for
     * reasons that have nothing to do with the page. Overlap asks the question directly: two runs
     * printed side by side share most of their vertical extent whatever their ascenders do.
     */
    private static List<List<RecognisedText>> lines(List<RecognisedText> page) {
        List<RecognisedText> byY = new ArrayList<>(page);
        byY.sort(Comparator.comparing(RecognisedText::y));

        List<List<RecognisedText>> lines = new ArrayList<>();
        for (RecognisedText r : byY) {
            List<RecognisedText> home = null;
            for (List<RecognisedText> line : lines) {
                if (overlapsVertically(line.get(0), r)) {
                    home = line;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                lines.add(home);
            }
            home.add(r);
        }
        return lines;
    }

    /** y is the BASELINE (see TesseractEngine), so a run's ink spans y-height to y. */
    private static boolean overlapsVertically(RecognisedText a, RecognisedText b) {
        float top = Math.max(a.y() - a.height(), b.y() - b.height());
        float bottom = Math.min(a.y(), b.y());
        float shared = bottom - top;
        return shared > 0 && shared >= SAME_LINE_OVERLAP * Math.min(a.height(), b.height());
    }

    /** Merge left to right while the gap stays inside a phrase. */
    private static List<RecognisedText> joinAlong(List<RecognisedText> line, float join) {
        List<RecognisedText> sorted = new ArrayList<>(line);
        sorted.sort(Comparator.comparing(RecognisedText::x));

        List<RecognisedText> out = new ArrayList<>();
        RecognisedText current = null;
        for (RecognisedText next : sorted) {
            if (current == null) {
                current = next;
            } else if (next.x() - (current.x() + current.width()) <= join) {
                current = merge(current, next);
            } else {
                out.add(current);
                current = next;
            }
        }
        if (current != null) out.add(current);
        return out;
    }

    /**
     * One run spanning both, joined by a single space.
     *
     * <p>Confidence is the MINIMUM of the two, not the mean. A phrase is only as trustworthy as its
     * least certain word, and averaging would let one shaky digit disappear into a long confident
     * description -- which is the reading a later routing decision would be made on.
     */
    private static RecognisedText merge(RecognisedText left, RecognisedText right) {
        float top = Math.min(left.y() - left.height(), right.y() - right.height());
        float baseline = Math.max(left.y(), right.y());
        return new RecognisedText(left.text() + " " + right.text(),
                left.x(), baseline,
                (right.x() + right.width()) - left.x(), baseline - top,
                left.pageIndex(), leastConfident(left.confidence(), right.confidence()));
    }

    /** Null stays null: a merged run whose parts reported nothing must not gain a number. */
    private static Float leastConfident(Float a, Float b) {
        if (a == null || b == null) return null;
        return Math.min(a, b);
    }

    private static float medianHeight(List<RecognisedText> runs) {
        List<Float> heights = new ArrayList<>(runs.stream().map(RecognisedText::height).toList());
        heights.sort(Float::compare);
        return heights.get(heights.size() / 2);
    }
}
