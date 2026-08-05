package com.finora.imports;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

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

    @Test
    void parseDate_recognizesADayAbbreviatedMonthYearFormat() {
        // Verified against a real Kotak Mahindra Bank statement -- "01 Jul 2026," a format none
        // of the numeric-only patterns matched, so every row on that file was silently dropped.
        assertThat(CsvParser.parseDate("01 Jul 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("25 Dec 2026")).isEqualTo(java.time.LocalDate.of(2026, 12, 25));
    }

    // --- header detection: abbreviated column names -------------------------------------------
    //
    // AMOUNT_HEADER_HINTS deliberately lists the abbreviated forms "withdrawal amt" and "deposit
    // amt" -- someone knew real statements use them. But normalizeHeaderCell strips only a trailing
    // parenthetical, never a trailing period, so the real cell "Withdrawal Amt." normalized to
    // "withdrawal amt." and could never equal the hint. The abbreviation was listed in a form the
    // normalizer cannot produce from the real string.
    //
    // The PDF engine had already solved this -- PdfTableLocator.matchesAnyHint tokenizes and strips
    // edge punctuation per word, and its comment names this exact string. The CSV engine never got
    // the fix.

    @Test
    void findHeaderRowIndex_findsAnHdfcStyleHeaderWhoseAmountColumnsAreAbbreviatedWithAPeriod() {
        // The failure this guards is total, not partial: with no amount column recognized,
        // findHeaderRowIndex returns -1 and PreviewGenerator surfaces every line of the file as
        // unparseable. The user uploads a valid bank export and stages zero transactions.
        List<String[]> rows = List.of(
                new String[]{"Date", "Narration", "Chq./Ref.No.", "Value Dt",
                        "Withdrawal Amt.", "Deposit Amt.", "Closing Balance"},
                new String[]{"01/07/2026", "UPI-SOME MERCHANT", "000000000001", "01/07/2026",
                        "500.00", "", "24500.00"}
        );

        assertThat(new CsvParser().findHeaderRowIndex(rows))
                .as("a header row whose only amount columns are abbreviated with a trailing period")
                .isZero();
    }

    @Test
    void normalizeHeaderCell_stripsTrailingPunctuationSoAbbreviationsMatchTheirHints() {
        assertThat(CsvParser.normalizeHeaderCell("Withdrawal Amt.")).isEqualTo("withdrawal amt");
        assertThat(CsvParser.normalizeHeaderCell("Deposit Amt.")).isEqualTo("deposit amt");
        assertThat(CsvParser.normalizeHeaderCell("Amount.")).isEqualTo("amount");
        assertThat(CsvParser.normalizeHeaderCell("Date.")).isEqualTo("date");
        // Still does everything it did before.
        assertThat(CsvParser.normalizeHeaderCell("Amount (INR)")).isEqualTo("amount");
        assertThat(CsvParser.normalizeHeaderCell("Withdrawal Amt.(INR)")).isEqualTo("withdrawal amt");
        assertThat(CsvParser.normalizeHeaderCell("  Closing Balance  ")).isEqualTo("closing balance");
        // Interior punctuation is untouched -- only the edges are noise.
        assertThat(CsvParser.normalizeHeaderCell("Chq./Ref.No.")).isEqualTo("chq./ref.no");
    }
}
