package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionTableDateRangeExtractorTest {

    private static PositionedText run(String text) {
        return new PositionedText(text, 0f, 0f, 0);
    }

    @Test
    void extract_readsTheRealKotakTableHeaderPhrasing() {
        var range = TransactionTableDateRangeExtractor.extract(
                List.of(run("Date  Transaction details from 16-Feb-2026 to 15-Mar-2026  Spends Area  Amount (Rs.)")));

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 2, 16));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void extract_returnsNone_whenNothingMatches() {
        var range = TransactionTableDateRangeExtractor.extract(
                List.of(run("Date  Narration  Amount  Balance")));

        assertThat(range).isSameAs(TransactionTableDateRangeExtractor.PrintedDateRange.NONE);
    }

    /** Bug fix: DATE_FORMATS used to carry two space-separated-month-name formats ("d MMM, yyyy" /
     *  "d MMM yyyy") that the extraction regex's own {@code \S+} capture groups can never satisfy --
     *  they stop at the first whitespace character by construction, so a token like "16 Feb, 2026"
     *  can only ever be captured as "16". Proves the dead entries are gone without silently degrading
     *  the one real, hyphenated shape this class is evidenced from. */
    @Test
    void extract_doesNotMatchASpaceSeparatedDate_becauseTheCaptureGroupStopsAtWhitespace() {
        var range = TransactionTableDateRangeExtractor.extract(
                List.of(run("Transaction details from 16 Feb, 2026 to 15 Mar, 2026")));

        assertThat(range).isSameAs(TransactionTableDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_doesNotMisfireOnAnUnrelatedNarrationSharingTheFromToShape() {
        var range = TransactionTableDateRangeExtractor.extract(
                List.of(run("IMPS transferred from A/c 1234 to A/c 5678")));

        assertThat(range).isSameAs(TransactionTableDateRangeExtractor.PrintedDateRange.NONE);
    }
}
