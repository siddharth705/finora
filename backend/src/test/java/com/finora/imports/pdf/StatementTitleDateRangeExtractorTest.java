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

    // Bug fix: DateTimeFormatter's default parsing is case-sensitive, so an all-caps month (the
    // style HSBC CC.pdf's own period line elsewhere in this corpus uses) used to match
    // BARE_DATE_RANGE's case-insensitive character class but then fail to parse, silently falling
    // through to NONE instead of recovering the same real fact isTitleRow's own equalsIgnoreCase
    // already tolerates for the title.
    @Test
    void extract_isTolerantOfDateMonthCasing() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("01 JUL 2026 - 31 JUL 2026", 33.9f, 126.0f)));

        assertThat(range.start()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(range.end()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    // Bug fix: the month token used to accept 3-9 letters, wider than DATE_FORMAT could ever
    // parse (only the 3-letter abbreviated form) -- a full month name matched the old regex and
    // then silently failed to parse. Narrowed to exactly 3 letters, so this now fails to match
    // structurally (NONE) rather than matching and then silently failing to parse.
    @Test
    void extract_returnsNone_whenTheMonthIsAFullNameRatherThanAThreeLetterAbbreviation() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("01 September 2026 - 30 September 2026", 33.9f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    // Bug fix: a reversed range (start printed after end) used to be accepted as-is -- the
    // structural checks (title, alignment, gap, date shape) have nothing to say about date
    // ORDER. Nothing in the real corpus prints one this way; this proves a malformed one would be
    // rejected rather than confidently reported backwards.
    @Test
    void extract_returnsNone_whenTheRangeIsReversed() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("31 Jul 2026 - 01 Jul 2026", 33.9f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    // Bug fix: the default (SMART) resolver style silently coerces an invalid calendar date --
    // confirmed directly that "30 Feb 2026" used to resolve to 2026-02-28 rather than being
    // rejected. STRICT makes a misread/malformed date fail to parse instead of confidently
    // reporting a different, wrong date.
    @Test
    void extract_returnsNone_whenTheDateIsNotARealCalendarDay() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("30 Feb 2026 - 31 Mar 2026", 33.9f, 126.0f)));

        assertThat(range).isSameAs(StatementTitleDateRangeExtractor.PrintedDateRange.NONE);
    }

    // Same STRICT-resolver fix, on the boundary that must still work: a real leap-year Feb 29.
    @Test
    void extract_stillAcceptsALeapYearFebruary29th() {
        var range = StatementTitleDateRangeExtractor.extract(List.of(
                run("Account Statement", 33.9f, 110.5f),
                run("01 Feb 2028 - 29 Feb 2028", 33.9f, 126.0f)));

        assertThat(range.end()).isEqualTo(LocalDate.of(2028, 2, 29));
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
