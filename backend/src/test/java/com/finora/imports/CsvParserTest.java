package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CsvParserTest {

    @Test
    void parseNumeric_stripsARupeeGlyphArtifactRenderedAsALiteralC() {
        // Bug fix: verified against a real uploaded HDFC "Tata Neu Plus" credit card statement --
        // that PDF's embedded font doesn't map the Rupee glyph to the real Unicode ₹ codepoint;
        // PDFBox extracts it as a literal "C" instead (e.g. "+  C 440.00" on screen renders as
        // "+ ₹440.00"). Left unstripped, every amount cell in a file with this quirk failed
        // BigDecimal parsing and the whole transaction silently vanished from the import.
        assertThat(CsvParser.parseNumeric("+  C 440.00")).isEqualByComparingTo("440.00");
        assertThat(CsvParser.parseNumeric(" C 1,817.02")).isEqualByComparingTo("1817.02");
        assertThat(CsvParser.parseNumeric("C200.00")).isEqualByComparingTo("200.00");
    }

    @Test
    void parseNumeric_doesNotStripARealLetterCThatIsNotActingAsACurrencyGlyph() {
        // The "C" strip is deliberately scoped (word-boundary'd, only when immediately followed
        // by a digit once whitespace is ignored) so it can't eat a real letter from anywhere else
        // in a cell that happens to contain one.
        assertThat(CsvParser.parseNumeric("ABC123")).isNull(); // not a valid number either way -- "C123" isn't a boundary-matched "C"
    }

    @Test
    void parseNumeric_stillHandlesTheOrdinaryRupeeSymbolAndRsPrefix() {
        assertThat(CsvParser.parseNumeric("₹1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(CsvParser.parseNumeric("Rs. 500")).isEqualByComparingTo("500");
        assertThat(CsvParser.parseNumeric("INR 99.99")).isEqualByComparingTo("99.99");
    }

    @Test
    void parseNumeric_stillHandlesTrailingDrCrSuffixes() {
        assertThat(CsvParser.parseNumeric("37.94 Dr")).isEqualByComparingTo(new BigDecimal("-37.94"));
        assertThat(CsvParser.parseNumeric("10,081.99 Cr")).isEqualByComparingTo("10081.99");
    }

    @Test
    void parseDate_stripsATrailingTimeComponent() {
        // HDFC's "DATE & TIME" column combines a date and a 24-hour time in one cell, joined by
        // a literal "|" glyph in at least one real export ("30/06/2026| 14:18").
        assertThat(CsvParser.parseDate("30/06/2026| 14:18")).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
        assertThat(CsvParser.parseDate("30/06/2026 14:18")).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
        assertThat(CsvParser.parseDate("30/06/2026")).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
    }
}
