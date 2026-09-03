package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfTableLocator#resolveYearlessDate}, {@link PdfTableLocator#yearsByPage}, and the
 * year-context-aware {@link PdfTableLocator#isTransactionShapedRow(List, Set)} overload, all
 * accessed via package-private test-only accessors. Motivated by a real HSBC credit-card
 * statement whose one transaction that cycle prints its date as a bare day+month with no year
 * ("30JUN"-shaped), relying on the statement period printed elsewhere on the same page to supply
 * it -- see this capability's own top-level doc comment above
 * {@code HEADERLESS_COLUMN_CLUSTER_TOLERANCE} in {@link PdfTableLocator}.
 */
class YearlessDateResolutionTest {

    private final PdfTableLocator locator = new PdfTableLocator();

    @Test
    void resolvesADayMonthDateWhenExactlyOneCandidateYearFits() {
        LocalDate resolved = locator.resolveYearlessDateForTest("30JUN", Set.of(2026));
        assertThat(resolved).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void acceptsASpaceOrHyphenBetweenDayAndMonth() {
        assertThat(locator.resolveYearlessDateForTest("30 JUN", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(locator.resolveYearlessDateForTest("30-JUN", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(locator.resolveYearlessDateForTest("30jun", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void returnsNullWhenNoCandidateYearIsGiven() {
        assertThat(locator.resolveYearlessDateForTest("30JUN", Set.of())).isNull();
    }

    @Test
    void returnsNullWhenTheTextIsNotDayMonthShaped() {
        assertThat(locator.resolveYearlessDateForTest("BBPS PMT", Set.of(2026))).isNull();
        assertThat(locator.resolveYearlessDateForTest("1,582.00", Set.of(2026))).isNull();
        // A full date already carries its own year -- this method's contract is yearless input
        // only, so a full date is not something it resolves (isTransactionShapedRow's own
        // CsvParser.parseDate check already handles this shape; overlapping responsibility here
        // would be redundant, not incorrect, but confuses which check owns which shape).
        assertThat(locator.resolveYearlessDateForTest("30 JUN 2026", Set.of(2026))).isNull();
    }

    @Test
    void returnsNullWhenTheDayMonthCombinationIsNotACalendarDate() {
        // 2026 is not a leap year -- 29 Feb 2026 does not exist. Must not silently coerce to 28
        // Feb the way java.time's SMART resolver style would (see this session's
        // StatementTitleDateRangeExtractor fix for the same class of bug).
        assertThat(locator.resolveYearlessDateForTest("29FEB", Set.of(2026))).isNull();
    }

    @Test
    void resolvesAFebTwentyNinthInALeapYearCandidate() {
        assertThat(locator.resolveYearlessDateForTest("29FEB", Set.of(2028)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void returnsNullWhenTwoCandidateYearsBothProduceAValidDate() {
        // Ambiguous: both 2025 and 2026 have a real 30 June. Never guess -- see this file's
        // established "fail-safe over fabrication" discipline.
        assertThat(locator.resolveYearlessDateForTest("30JUN", Set.of(2025, 2026))).isNull();
    }

    // ---------------------------------------------------------------------------------------
    // A billing period that crosses a year boundary.
    //
    // All fixtures below are SYNTHETIC (synthetic-ok): the shapes are taken from a real HSBC
    // credit-card statement, but every value here is invented. That document printed a period
    // running from late December of one year to late January of the next, and dated its
    // transactions with a bare day+month. Two things went wrong together, and both are pinned
    // here:
    //
    //   1. The period's two full dates were printed inside ONE composite text run alongside other
    //      tokens, so asking CsvParser.parseDate about the whole cell failed and the EARLIER year
    //      never entered the candidate set at all.
    //   2. With only the later year as a candidate, a December transaction resolved -- perfectly
    //      "unambiguously", by the old rule -- to a date a full year in the future.
    //
    // Fixing only (1) is not enough: it makes the December date genuinely ambiguous, which under
    // the old rule means null, which drops a real row. The interval the page's own dates span is
    // what breaks the tie.
    // ---------------------------------------------------------------------------------------

    @Test
    void collectsAYearFromAFullDatePrintedInsideACompositeRun() {
        // One cell carrying a whole period line, the way a redacted account number, both period
        // dates and a balance can arrive glued into a single positioned run.
        List<PositionedText> row = List.of(
                new PositionedText("XXXXXXXXXXX 24 DEC 2025 To 23 JAN 2026 1,234.56", 10f, 10f, 0));

        Map<Integer, PdfTableLocator.PageDateEvidence> byPage =
                locator.yearsByPageForTest(List.of(row));

        assertThat(byPage.get(0).years())
                .as("both of the period's years are printed on the page; neither parses as a "
                        + "whole cell, and before this both were invisible")
                .containsExactlyInAnyOrder(2025, 2026);
        assertThat(byPage.get(0).earliest()).isEqualTo(LocalDate.of(2025, 12, 24));
        assertThat(byPage.get(0).latest()).isEqualTo(LocalDate.of(2026, 1, 23));
    }

    @Test
    void embeddedDateScanStillIgnoresTextThatMerelyContainsDigits() {
        // Invented values in the SHAPES real statements use (synthetic-ok): a long zero-padded
        // reference number, a percentage-bearing tax label, and two amounts sharing one cell.
        // None of these should look like a date to the window scan.
        List<PositionedText> row = List.of(
                new PositionedText("REF 0000111122223333 4", 10f, 10f, 0),
                new PositionedText("SAMPLE TAX LABEL @07.50%", 40f, 10f, 0),
                new PositionedText("11,111.00 2,222.00", 90f, 10f, 0));

        Map<Integer, PdfTableLocator.PageDateEvidence> byPage =
                locator.yearsByPageForTest(List.of(row));

        assertThat(byPage.get(0).years()).isEmpty();
    }

    @Test
    void resolvesADecemberDateIntoTheEarlierYearWhenThePagesOwnDatesBracketIt() {
        // The page prints the period's start (late December, earlier year) and a payment due date
        // (February, later year). A bare "24DEC" is a real date in both years; only one of them
        // falls inside what this page's own dates span.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 12, 24),
                LocalDate.of(2026, 1, 23),
                LocalDate.of(2026, 2, 10)));

        assertThat(locator.resolveYearlessDateForTest("24DEC", evidence))
                .isEqualTo(LocalDate.of(2025, 12, 24));
    }

    @Test
    void resolvesAJanuaryDateIntoTheLaterYearFromTheSameEvidence() {
        // The same evidence, the other side of the boundary -- this is what makes the rule a
        // bracket test rather than a "prefer the earlier year" preference.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 12, 24),
                LocalDate.of(2026, 1, 23),
                LocalDate.of(2026, 2, 10)));

        assertThat(locator.resolveYearlessDateForTest("01JAN", evidence))
                .isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(locator.resolveYearlessDateForTest("23JAN", evidence))
                .isEqualTo(LocalDate.of(2026, 1, 23));
    }

    @Test
    void declinesWhenThePagesOwnDatesBracketBothCandidates() {
        // A page spanning more than a year brackets both "05MAR"s. Genuinely ambiguous, so this
        // still returns null rather than picking a favourite -- the fail-safe posture the method's
        // own doc comment describes is unchanged by the tie-break.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2026, 12, 31)));

        assertThat(locator.resolveYearlessDateForTest("05MAR", evidence)).isNull();
    }

    @Test
    void declinesWhenThePagesOwnDatesBracketNeitherCandidate() {
        // Both candidate years are present, so the year set alone cannot decide -- and the
        // interval is positioned so that BOTH candidates fall just outside it, one before its
        // start and one after its end. Getting here needs a page whose own dates straddle a year
        // boundary narrowly; the arithmetic is easy to get wrong by eye, which is the point of
        // pinning it. Interval: 15 Jan of the earlier year to 3 Jan of the later one.
        // Candidates for "05JAN": the 5th of Jan in each year -- ten days too early for the
        // interval's start, and two days too late for its end.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2026, 1, 3)));

        assertThat(locator.resolveYearlessDateForTest("05JAN", evidence)).isNull();
    }

    @Test
    void resolvesWhenOnlyOneCandidateEscapesANarrowYearBoundaryInterval() {
        // The companion to the test above, and the reason it needs such a specific interval: move
        // the interval's end out by a few days and the later candidate falls inside it again, so
        // exactly one is bracketed and the tie-break resolves. Pinned so a future change to the
        // interval comparison (inclusive vs exclusive bounds) fails loudly here.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 1, 15),
                LocalDate.of(2026, 1, 31)));

        assertThat(locator.resolveYearlessDateForTest("05JAN", evidence))
                .isEqualTo(LocalDate.of(2026, 1, 5));
    }

    @Test
    void theIntervalBoundsAreInclusive() {
        // A candidate landing exactly on the interval's first or last day counts as bracketed.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 1, 5),
                LocalDate.of(2026, 1, 3)));

        assertThat(locator.resolveYearlessDateForTest("05JAN", evidence))
                .isEqualTo(LocalDate.of(2025, 1, 5));
    }

    @Test
    void aSingleCandidateYearStillResolvesWithoutConsultingTheInterval() {
        // The pre-existing rule is untouched: one candidate year that yields a real calendar date
        // resolves, whether or not the page's interval happens to contain it. Narrowing that would
        // regress every document already relying on it.
        PdfTableLocator.PageDateEvidence evidence =
                PdfTableLocator.PageDateEvidence.of(List.of(LocalDate.of(2026, 6, 24)));

        assertThat(locator.resolveYearlessDateForTest("30NOV", evidence))
                .isEqualTo(LocalDate.of(2026, 11, 30));
    }

    @Test
    void anImpossibleCalendarDateIsStillExcludedBeforeTheIntervalIsConsulted() {
        // 29 Feb exists in exactly one of these two years, so there is nothing for the interval
        // rule to decide -- the leap-year check already made it unambiguous.
        PdfTableLocator.PageDateEvidence evidence = PdfTableLocator.PageDateEvidence.of(List.of(
                LocalDate.of(2025, 1, 1),
                LocalDate.of(2024, 3, 1)));

        assertThat(locator.resolveYearlessDateForTest("29FEB", evidence))
                .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void yearsByPageGroupsFullDatesByTheirOwnPageIndependently() {
        List<PositionedText> page0Row = List.of(
                new PositionedText("24 JUN 2026", 10f, 10f, 0));
        List<PositionedText> page1Row = List.of(
                new PositionedText("01 JAN 2025", 10f, 10f, 1));
        Map<Integer, PdfTableLocator.PageDateEvidence> byPage =
                locator.yearsByPageForTest(List.of(page0Row, page1Row));
        assertThat(byPage.get(0).years()).containsExactly(2026);
        assertThat(byPage.get(1).years()).containsExactly(2025);
    }

    @Test
    void yearsByPageIgnoresCellsThatAreNotFullDates() {
        List<PositionedText> row = List.of(
                new PositionedText("30JUN", 10f, 10f, 0),
                new PositionedText("BBPS PMT", 40f, 10f, 0),
                new PositionedText("24 JUN 2026", 90f, 10f, 0));
        Map<Integer, PdfTableLocator.PageDateEvidence> byPage = locator.yearsByPageForTest(List.of(row));
        assertThat(byPage.get(0).years()).containsExactly(2026);
    }

    @Test
    void transactionShapeAdmitsAYearlessDateRowWhenAYearContextIsGiven() {
        List<PositionedText> row = List.of(
                new PositionedText("30JUN", 10f, 10f, 0),
                new PositionedText("BBPS PMT reference", 40f, 10f, 0),
                new PositionedText("1,582.00", 200f, 10f, 0));
        assertThat(locator.isTransactionShapedRowForTest(row, Set.of())).isFalse();
        assertThat(locator.isTransactionShapedRowForTest(row, Set.of(2026))).isTrue();
    }

    /** Same convention as {@link HeaderlessLayoutInferenceTest}'s own {@code run}/{@code amount}
     *  helpers -- kept local rather than shared, matching that file's own choice not to extract a
     *  shared builder across headerless-capability test files (each one's fixture shape is
     *  distinct enough that a shared abstraction would obscure more than it saves). */
    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return run(text, endX - width, width, y);
    }

    /** One transaction line using a {@link PdfTableLocator#WEAK_DAY_MONTH}-shaped date (no year)
     *  in both the Date and Value Date columns -- the real HSBC credit-card shape this whole
     *  capability was built for. Otherwise identical in column geometry to {@link
     *  HeaderlessLayoutInferenceTest}'s own {@code transaction} helper. */
    private static List<PositionedText> yearlessTransaction(String yearlessDate, String narration,
            String debitText, String creditText, String balanceText, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(yearlessDate, 30f, 55f, y));
        row.add(run(yearlessDate, 95f, 55f, y));
        row.add(run(narration, 165f, narration.length() * 5.2f, y));
        row.add(amount(debitText, 390f, y));
        row.add(amount(creditText, 520f, y));
        row.add(amount(balanceText, 650f, y));
        return row;
    }

    @Test
    void headerlessInferenceResolvesYearlessDatesAcrossThreeTransactionRows() {
        List<PositionedText> positioned = new ArrayList<>();
        // Account-summary furniture -- carries the only full (year-bearing) date on the page, the
        // same shape a real credit card's payment-summary panel prints. Each bound of the period
        // is its own PositionedText run, matching how PDFBox actually splits text
        // (CsvParser.parseDate cannot parse a whole "X To Y" phrase as one string).
        positioned.add(run("Statement period", 30f, 90f, 200f));
        positioned.add(run("24 JUN 2026", 165f, 70f, 200f));
        positioned.add(run("To", 240f, 20f, 200f));
        positioned.add(run("23 JUL 2026", 265f, 70f, 200f));

        List<List<PositionedText>> rows = List.of(
                yearlessTransaction("25JUN", "MERCHANT PAYMENT ONE REFERENCE", "1582.00", "-", "8418.00", 300f),
                yearlessTransaction("30JUN", "MERCHANT PAYMENT TWO REFERENCE", "240.00", "-", "8178.00", 320f),
                yearlessTransaction("05JUL", "MERCHANT PAYMENT THREE REFERENCE", "-", "99.00", "8277.00", 340f));
        for (List<PositionedText> row : rows) positioned.addAll(row);

        PdfTableLocator.LocatedDocument doc = locator.locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> stagedRows = doc.sections().get(0).rows();
        assertThat(stagedRows).hasSize(3);
        assertThat(stagedRows).extracting(r -> r.get("Date"))
                .allSatisfy(dateText -> assertThat(CsvParser.parseDate(dateText)).isNotNull());
        assertThat(stagedRows.get(0)).containsEntry("Debit", "1582.00");
        assertThat(stagedRows.get(2)).containsEntry("Credit", "99.00");
    }
}
