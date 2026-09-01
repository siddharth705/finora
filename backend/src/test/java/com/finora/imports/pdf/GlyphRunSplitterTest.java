package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlyphRunSplitterTest {

    private static GlyphRunSplitter.Glyph glyph(String text, float x, float endX, float fontSize) {
        return new GlyphRunSplitter.Glyph(text, x, endX, 100f, fontSize);
    }

    private static List<String> texts(List<List<GlyphRunSplitter.Glyph>> segments) {
        return segments.stream()
                .map(seg -> seg.stream().map(GlyphRunSplitter.Glyph::text).reduce("", String::concat))
                .toList();
    }

    // Coordinates copied verbatim from a direct PDFBox TextPosition inspection of the real SC bank
    // statement's own table header row: "Value" ends at x=103.09, "Description" starts at
    // x=180.55 (a 75.52pt gap, no intervening glyph) -- font 7pt, so 75.52/7 = 10.8x. This is the
    // exact real-document shape GlyphRunSplitter exists to split.
    @Test
    void split_separatesTwoHeaderCells_whenPdfBoxMergedThemAcrossAWideGap() {
        var glyphs = List.of(
                glyph("V", 84.41f, 89.08f, 7f), glyph("a", 89.08f, 92.97f, 7f),
                glyph("l", 92.97f, 94.92f, 7f), glyph("u", 94.92f, 99.20f, 7f),
                glyph("e", 99.20f, 103.09f, 7f), glyph("D", 180.55f, 185.60f, 7f),
                glyph("e", 185.60f, 189.50f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("Value", "De");
    }

    // Same real document, a narrower real merge: "Cheque" ends at x=366.09, "Deposit" starts at
    // x=395.46 (29.37pt gap) -- font 7pt, so 29.37/7 = 4.2x, the SMALLEST of the real gaps this
    // splitter was evidenced against. Confirms the threshold has real margin, not just enough for
    // the widest case.
    @Test
    void split_separatesTwoHeaderCells_atTheSmallestRealGapMeasured() {
        var glyphs = List.of(
                glyph("e", 360.25f, 364.14f, 7f), glyph(" ", 364.14f, 366.09f, 7f),
                glyph("D", 395.46f, 400.51f, 7f), glyph("e", 400.51f, 404.41f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("e ", "De");
    }

    @Test
    void split_keepsOrdinaryWordsTogether_whenSeparatedOnlyByARealSpaceGlyph() {
        var glyphs = List.of(
                glyph("D", 43.72f, 48.77f, 7f), glyph("a", 48.77f, 52.67f, 7f),
                glyph("t", 52.67f, 55.00f, 7f), glyph("e", 55.00f, 58.89f, 7f),
                glyph(" ", 58.89f, 60.47f, 7f), glyph("B", 60.47f, 65.52f, 7f));

        var result = GlyphRunSplitter.split(glyphs);

        assertThat(texts(result)).containsExactly("Date B");
    }

    @Test
    void split_returnsOneSegment_forASingleGlyph() {
        assertThat(texts(GlyphRunSplitter.split(List.of(glyph("X", 10f, 15f, 10f)))))
                .containsExactly("X");
    }

    @Test
    void split_returnsEmpty_forEmptyInput() {
        assertThat(GlyphRunSplitter.split(List.of())).isEmpty();
    }

    /** A gap right at 3x font size (the boundary itself) must NOT split -- only a gap strictly
     *  greater than the threshold does, matching the {@code >} comparison in the implementation. */
    @Test
    void split_doesNotSplit_atExactlyTheThreshold() {
        var glyphs = List.of(glyph("A", 0f, 10f, 10f), glyph("B", 40f, 50f, 10f)); // gap = 30 = 3x10

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("AB");
    }

    @Test
    void split_splits_justOverTheThreshold() {
        var glyphs = List.of(glyph("A", 0f, 10f, 10f), glyph("B", 40.1f, 50.1f, 10f)); // gap = 30.1 > 3x10

        assertThat(texts(GlyphRunSplitter.split(glyphs))).containsExactly("A", "B");
    }
}
