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
 */
public record PositionedText(String text, float x, float y, int pageIndex) {
}
