package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StatementTitleDateRangeExtractorTest {

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0);
    }

    private static PositionedText run(String text, float x, float y, int page) {
        return new PositionedText(text, x, y, page);
    }

    // Coordinates copied verbatim from the committed real trace
    // (src/test/resources/traces/kotak-savings-ledger-validation.trace, rows at y=110.52/126.04,
    // x=33.86) -- the trace itself has its date text redacted ("99 Xxx 9999 - 99 Xxx 9999", the
    // same policy every transaction date in that trace is redacted under), so it cannot prove
    // correct date VALUE recovery on its own; this test supplies the real, unredacted date string
    // confirmed separately via direct PositionedText inspection of the original PDF at these exact
    // positions. See KotakSavingsTitleDateRangeRegressionTest for the structural-only assertion run
    // directly against the committed trace.
    @Test
    void extract_readsTheRealKotakSavingsTitleShape() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.86f, 110.52f),
                run("01 Jul 2026 - 31 Jul 2026", 33.86f, 126.04f)));

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void extract_isTolerantOfTitleCasing() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("ACCOUNT STATEMENT", 33.9f, 110.5f),
                run("01 Jul 2026 - 31 Jul 2026", 33.9f, 126.0f)));

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void extract_returnsNone_whenNoTitleRowPrecedesTheDateRange() {
        // Proves this is not a generic "find any bare date range" pattern -- the same date row
        // with nothing recognisable above it must not match.
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Some Other Heading", 33.9f, 110.5f),
                run("01 Jul 2026 - 31 Jul 2026", 33.9f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_whenTheRowBelowIsNotABareDateRange() {
        // A labelled range (already handled elsewhere in the pipeline) must not also match here --
        // this extractor is for the unlabeled shape only.
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("Statement Period: 01 Jul 2026 - 31 Jul 2026", 33.9f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_whenTheDateRowIsNotLeftAlignedWithTheTitle() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("01 Jul 2026 - 31 Jul 2026", 220.0f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_whenTheVerticalGapIsTooLarge() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("01 Jul 2026 - 31 Jul 2026", 33.9f, 400.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_whenTheTitleAndDateRowAreOnDifferentPages() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f, 0),
                run("01 Jul 2026 - 31 Jul 2026", 33.9f, 20.0f, 1)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_whenNothingMatchesAtAll() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Some Customer Name", 35.7f, 171.2f),
                run("Account No. 1000200030004000", 326.9f, 166.5f))); // synthetic-ok

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    @Test
    void extract_returnsNone_onEmptyInput() {
        assertThat(StatementTitleDateRangeExtractor.extract(List.of()))
                .isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }
}
