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
public record PositionedText(String text, float x, float y, int pageIndex, float width) {

    public PositionedText(String text, float x, float y, int pageIndex) {
        this(text, x, y, pageIndex, 0f);
    }

    /** The run's right edge — the coordinate that is stable under right alignment. */
    public float endX() {
        return x + width;
    }
}
