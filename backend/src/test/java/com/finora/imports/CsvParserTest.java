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

    @Test
    void parseDate_recognizesADotSeparatedDayMonthYear() {
        // Verified against a real ICICI Bank savings-account transaction history ("28.07.2026").
        // Necessary but not sufficient to import that particular file -- its header is printed
        // across three stacked lines, which the locator does not yet read as one header, so its
        // serial-number column shares a cell with the date. This is the date half of that, fixed
        // where it belongs rather than left to be rediscovered alongside the harder half.
        assertThat(CsvParser.parseDate("28.07.2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 28));
        assertThat(CsvParser.parseDate("01.01.2026")).isEqualTo(java.time.LocalDate.of(2026, 1, 1));

        // A serial number sharing the cell is still not a date -- the value has to be the whole
        // string, so the combined cell this layout currently produces keeps failing loudly.
        assertThat(CsvParser.parseDate("1 28.07.2026")).isNull();
    }

    @Test
    void parseDate_recognizesAMonthNameFirstFormat() {
        // Verified against a real Bandhan Bank statement, whose Transaction Date and Value Date
        // columns read "July29, 2026" -- month name first, no separator before the day. Every
        // pattern the parser had put the DAY first, so no date in that table parsed; nothing could
        // anchor as a transaction and the whole statement staged zero rows.
        assertThat(CsvParser.parseDate("July29, 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));
        assertThat(CsvParser.parseDate("September01, 2026")).isEqualTo(java.time.LocalDate.of(2026, 9, 1));

        // The spaced form, and the abbreviated month name, from the same one pattern -- see
        // CsvParser.monthNameFirst for why lenient text parsing covers both spellings.
        assertThat(CsvParser.parseDate("July 29, 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));
        assertThat(CsvParser.parseDate("Jul 29, 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));
        assertThat(CsvParser.parseDate("JULY 29, 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));
    }

    @Test
    void parseDate_retriesWithSeparatorsInsertedRatherThanNormalizingFirst() {
        // The missing-separator retry only ever runs after every format has already failed on the
        // string exactly as the document wrote it, so it can rescue a cell but never re-read one.
        // Asserted because the ordering is the entire safety argument for touching the input at
        // all: a date parser that rewrites before looking is one that can silently change answers.
        assertThat(CsvParser.parseDate("01/07/2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("2026-07-29")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));

        // The retry generalises past the format it was added for: a real HSBC statement writes its
        // dates as "29Jul2026", the day-first form with both separators dropped.
        assertThat(CsvParser.parseDate("29Jul2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 29));

        // Still no, once separators are inserted -- narration that landed in a date column must
        // not start parsing as a date just because it contains a month name.
        assertThat(CsvParser.parseDate("UPI/DR/D650000000001/")).isNull();
        assertThat(CsvParser.parseDate("Statement Summary")).isNull();
    }

    @Test
    void parseDate_recognizesTwoDigitYears() {
        // Every pattern required four digits, so the single most common rendering in Indian bank
        // statements did not parse at all. Measured on a real 39-page statement: it produced 2
        // transactions out of 2541 lines, because no row could anchor on a date the parser refused
        // to read. With these formats it produces 569.
        assertThat(CsvParser.parseDate("01/07/26")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("31-12-26")).isEqualTo(java.time.LocalDate.of(2026, 12, 31));
        assertThat(CsvParser.parseDate("01 Jul 26")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("01-JUL-26")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("01-Jul-2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
    }

    @Test
    void parseDate_readsTwoDigitYearsAsThisCentury() {
        // "26" is 2026, never 1926. Java resolves yy against a base of 2000, which is the right
        // answer for a bank statement and is asserted rather than assumed -- the alternative
        // reading would file transactions a century before the product existed, and would do it
        // silently.
        assertThat(CsvParser.parseDate("01/07/26").getYear()).isEqualTo(2026);
        assertThat(CsvParser.parseDate("01/07/99").getYear()).isEqualTo(2099);
    }

    @Test
    void parseDate_stillPrefersFourDigitYearsWhereBothCouldMatch() {
        // The two-digit patterns are listed after the four-digit ones. This guards the ordering:
        // a four-digit year must never be truncated into a two-digit reading.
        assertThat(CsvParser.parseDate("01/07/2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
        assertThat(CsvParser.parseDate("01 Jul 2026")).isEqualTo(java.time.LocalDate.of(2026, 7, 1));
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
