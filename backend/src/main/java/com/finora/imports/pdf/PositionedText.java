package com.finora.imports.pdf;

/**
 * One contiguous text run PDFBox identified during extraction, with its position. This is the
 * mechanical, structure-only output of {@link PdfTextExtractor} -- deliberately as ignorant of
 * "what this text means" as {@code CsvParser}'s raw {@code String[]} rows are; interpretation
 * (which row, which column, what the value represents) is entirely {@link PdfTableLocator}'s and
 * {@link PdfMetadataExtractor}'s job, not this class's.
 *
 * x/y are PDF user-space coordinates (points, 1/72 inch), already direction-adjusted for page
 * rotation (PDFBox's {@code TextPosition.getXDirAdj()}/{@code getYDirAdj()}) -- using the
 * non-adjusted coordinates would put text in the wrong place for any rotated page.
 *
 * <p>{@code width} is the run's rendered horizontal extent, so {@link #endX()} gives its RIGHT
 * edge. x alone is not enough to place a run in a column: financial documents right-align numeric
 * columns, and under right alignment the left edge moves with the value's length while the right
 * edge stays put. A short value therefore sits further right than a long one in the SAME column --
 * which is how a real statement put a withdrawal of "0.00" into the deposits column while "20.00"
 * and "436.00" on neighbouring rows bucketed correctly. See PdfTableLocator's RIGHT_ALIGNED_AMOUNTS.
 *
 * <p>Defaults to 0 when unknown, via the 4-arg constructor. Zero width means {@code endX() == x},
 * so any caller that cannot supply a width — hand-built test fixtures, recorded traces predating
 * this field — keeps exactly the left-edge behaviour it had before, and only documents carrying
 * real measurements get the alignment-aware placement.
 */
public record PositionedText(String text, float x, float y, int pageIndex, float width,
                              float height, Float confidence, TextSource source) {

    /**
     * @param height     the run's vertical extent. Defaults to 0 (unknown) on every path that has
     *                   never measured one, exactly as {@code width} did before it was measured.
     *                   Nothing reads it yet; it completes the bounding box a recognised run needs
     *                   in order to be located on a page image at all.
     * @param confidence how sure the acquisition was of these characters, or <b>null</b> when the
     *                   question does not apply. Null is the honest value for native extraction:
     *                   it does not estimate, it reads what the file states, and stamping 1.0 there
     *                   would be a claim about correctness rather than a report of certainty. Only
     *                   a recogniser produces this.
     * @param source     which mechanism produced the run — never null.
     */
    public PositionedText {
        if (source == null) source = TextSource.NATIVE_PDF;
    }

    /** Native extraction with a measured width, which is every caller that predates acquisition
     *  provenance. Behaviour is identical to before: no height, no confidence, native source. */
    public PositionedText(String text, float x, float y, int pageIndex, float width) {
        this(text, x, y, pageIndex, width, 0f, null, TextSource.NATIVE_PDF);
    }

    public PositionedText(String text, float x, float y, int pageIndex) {
        this(text, x, y, pageIndex, 0f);
    }

    /** The run's right edge — the coordinate that is stable under right alignment. */
    public float endX() {
        return x + width;
    }

    /** The run's bottom edge. Zero height leaves this equal to {@code y}, the same way an
     *  unmeasured width leaves {@link #endX()} equal to {@code x}. */
    public float endY() {
        return y + height;
    }

    /** True when this run was recognised rather than read, and so can be confidently wrong. */
    public boolean isRecognised() {
        return source == TextSource.OCR;
    }
}
