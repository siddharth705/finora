package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.imports.pdf.fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WRAPPED_HEADER: one heading row printed across two visual lines.
 *
 * <p>See {@code PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample} for the real
 * fixed-deposit schedule this is modeled on, and why NEITHER of its two heading lines is a header
 * on its own — the upper carries the column names but no date word, the lower carries the date
 * word but too few recognized names to clear the density check. Nine deposits were invisible to
 * table location entirely, and the import reported success.
 *
 * <p>Asserted at the locator rather than through a preview because the failure was structural
 * rather than a bad value: with no header recognized there was no table, so there was no row to
 * be wrong. The two halves are asserted separately — that the table is found at all, and that its
 * columns carry BOTH lines' text — because merging the wrong way round (or on the wrong x) still
 * finds a table, just one whose columns are named after half a heading and anchored under the
 * wrong values.
 */
class WrappedHeaderPdfTableLocatorTest {

    private PdfTableLocator.LocatedDocument locate(DocumentContext ctx) throws Exception {
        byte[] bytes = PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample();
        return new PdfTableLocator().locateAll(new PdfTextExtractor().extract(bytes), ctx);
    }

    @Test
    void aHeadingSplitAcrossTwoLinesIsStillAHeader() throws Exception {
        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");

        PdfTableLocator.LocatedDocument doc = locate(ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows())
                .as("three deposits, where before the fix there was no table at all")
                .hasSize(3);
        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
    }

    @Test
    void everyColumnIsNamedFromBothLinesOfItsHeading() throws Exception {
        Map<String, String> first = locate(null).sections().get(0).rows().get(0);

        // Not "FD"/"Number" as two columns, and not one line's text with the other's dropped.
        assertThat(first.keySet()).containsExactly(
                "FD Number", "Currency Code", "Deposit Principal", "Open/Value Date",
                "Rate Of Interest", "Maturity Amount", "Nomination Registered");
    }

    @Test
    void everyValueLandsUnderItsOwnColumn() throws Exception {
        List<Map<String, String>> rows = locate(null).sections().get(0).rows();

        // The column names alone can be right while the anchors are not: the merged heading's
        // anchor has to be the leftmost edge of BOTH its lines, or a value sitting between the two
        // buckets one column over. Every cell is asserted rather than just the date, because a
        // single-column slip leaves the rest looking correct.
        assertThat(rows.get(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "FD Number", "FD0000001",
                "Currency Code", "INR",
                "Deposit Principal", "50,000.00",
                "Open/Value Date", "12/01/2026",
                "Rate Of Interest", "7.10",
                "Maturity Amount", "53,551.00",
                "Nomination Registered", "YES"));

        assertThat(rows).allSatisfy(row -> assertThat(CsvParser.parseDate(row.get("Open/Value Date")))
                .as("each deposit keeps a parseable date, so none is dropped downstream")
                .isNotNull());
    }

    /**
     * Three lines, not two. {@code HEADER_WRAP_MAX_LINES} allows a heading to wrap onto a third
     * line -- the real statement this capability came from prints its page-0 account summary that
     * way ("CR / Limit / ..." above "Ccy / Account Type / Balance" above "DR / Amount / Balance").
     * That document's own instance is never located, for the unrelated reason that it has no date
     * column at all, so the depth the constant claims had no test reaching it. A merge that can
     * rewrite a table's headings is the wrong place to leave an unexercised branch.
     *
     * <p>Built from positioned runs rather than a rendered fixture so the runs carry measured
     * widths, which exercises the span-overlap join. The trace and the fixture above both come
     * from width-less input and can only reach the nearest-anchor fallback.
     */
    @Test
    void aHeadingSplitAcrossThreeLinesIsMergedAcrossAllOfThem() {
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                run("Txn", 50f, 15f, 100f), run("Transaction", 150f, 55f, 100f),
                run("Withdrawal", 300f, 52f, 100f), run("Closing", 430f, 36f, 100f),

                run("Date", 52f, 20f, 108f), run("Details", 155f, 33f, 108f),
                run("Amount", 305f, 33f, 108f), run("Balance", 433f, 36f, 108f),

                run("(DD/MM)", 48f, 35f, 116f), run("and Ref", 152f, 33f, 116f),
                run("(INR)", 302f, 26f, 116f), run("(INR)", 431f, 26f, 116f)));
        runs.addAll(List.of(
                run("12/01/2026", 50f, 45f, 140f), run("UPI PAYMENT", 150f, 58f, 140f),
                run("1,250.00", 300f, 38f, 140f), run("8,750.00", 430f, 38f, 140f),
                run("14/01/2026", 50f, 45f, 160f), run("CARD PAYMENT", 150f, 62f, 160f),
                run("2,000.00", 300f, 38f, 160f), run("6,750.00", 430f, 38f, 160f)));

        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER");
        // All three lines, in print order. Stopping at the first span that scored would give
        // "Txn Date" and silently drop the third line's cells onto the table as a data row.
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(0).rows().get(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Txn Date (DD/MM)", "12/01/2026",
                "Transaction Details and Ref", "UPI PAYMENT",
                "Withdrawal Amount (INR)", "1,250.00",
                "Closing Balance (INR)", "8,750.00"));
    }

    private static PositionedText run(String text, float x, float width, float y) {
        return run(text, x, width, y, 0);
    }

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    /**
     * The invariant this capability is allowed to have, stated executably rather than left to a
     * regeneratable snapshot: it may make a table appear that was not being located, and it may
     * not disturb one that was.
     *
     * <p>Two of the three committed traces contain no wrapped heading at all, so the capability
     * must never fire on them; the third has two, and the savings ledger it was already extracting
     * has to come through with exactly the rows and columns it had before. A future change that
     * turns this into a general row-merging engine breaks this test before it breaks a customer's
     * statement.
     */
    @Test
    void aTableThatWasAlreadyBeingLocatedIsNotDisturbed() {
        for (String trace : List.of("hdfc-txn-date-narration-header", "bob-repeated-account-banner")) {
            DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
            new PdfTableLocator().locateAll(PdfTrace.load(trace), ctx);
            assertThat(ctx.capabilities()).extracting("capability")
                    .as("%s has no wrapped heading -- this capability must not touch it", trace)
                    .doesNotContain("WRAPPED_HEADER");
        }

        PdfTableLocator.LocatedSection savings = new PdfTableLocator()
                .locateAll(PdfTrace.load("hdfc-composite-deposit-schedules"), null).sections().get(0);

        assertThat(savings.rows()).hasSize(84);
        assertThat(savings.rows().get(0).keySet())
                .containsExactly("Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance");
        long dated = savings.rows().stream()
                .map(row -> CsvParser.firstNonBlank(row, "txn date"))
                .filter(date -> date != null && CsvParser.parseDate(date.trim()) != null)
                .count();
        assertThat(dated).as("the savings ledger's own transactions, unchanged").isEqualTo(76L);
    }

    /**
     * The negative case that pins the span bound, and the reason it must not be relaxed.
     *
     * <p>The geometry is measured from page 10 of the real statement -- x AND width, which is the
     * whole point of this test. Below its fixed-deposit heading, a SECOND heading tier is printed
     * for the second visual line of each deposit record: two lines, 9pt apart, neither carrying a
     * date or a number, which is every positive signal a wrapped heading has. Merged, it scores as
     * a header (an "amount" cell, a "date" cell, three cells, comfortably dense). It is refused
     * only because its rightmost cell sits 166pt from any column anchor on the line above it.
     *
     * <p>Left to merge, it arrives immediately after the heading it follows, splits the table it
     * belongs to, and re-anchors that table on two columns -- which is exactly what the real
     * statement did before {@code columnFor} stopped consulting span overlap. This test PASSED
     * against that bug, because an earlier version of it invented a plausible-looking width of 72
     * for "Maturity Available". PDFBox measures that run at <b>214.80</b>: a run's width is its
     * advance, and the wide gap between the two words is inside it, so the span reached x=476.26
     * and swallowed "Withdrawable***" at [428.02, 489.36]. The measured widths below are what make
     * this a regression test rather than a reassuring one.
     *
     * <p>Anyone who later reads the recurring-deposit half-naming limitation and reaches for "why
     * not just relax the span condition" should fail here first: this is the measurement that says
     * what relaxing it costs.
     */
    @Test
    void aSecondHeadingTierOutsideTheColumnsAboveItIsNotAHeader() {
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                run("Txn Date", 50f, 33f, 100f), run("Narration", 150f, 36f, 100f),
                run("Withdrawals", 300f, 47f, 100f), run("Closing Balance", 430f, 62f, 100f),

                run("12/01/2026", 50f, 45f, 120f), run("UPI PAYMENT", 150f, 58f, 120f),
                run("1,250.00", 300f, 38f, 120f), run("8,750.00", 430f, 38f, 120f),
                run("14/01/2026", 50f, 45f, 140f), run("CARD PAYMENT", 150f, 62f, 140f),
                run("2,000.00", 300f, 38f, 140f), run("6,750.00", 430f, 38f, 140f)));

        // The tier, at its measured x AND measured width -- 214.80 is not a typo.
        runs.addAll(List.of(
                run("Current FD Amount #", 153.21f, 80.89f, 170f),
                run("Maturity Available", 261.46f, 214.80f, 170f),
                run("Date **", 265.24f, 25.78f, 179f),
                run("Withdrawable***", 428.02f, 61.34f, 179f)));

        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting("capability").doesNotContain("WRAPPED_HEADER");
        assertThat(doc.sections())
                .as("one table -- the tier must not open a second one")
                .hasSize(1);
        assertThat(doc.sections().get(0).rows().get(0).keySet())
                .as("still anchored on the real heading, not re-anchored on the tier")
                .containsExactly("Txn Date", "Narration", "Withdrawals", "Closing Balance");
    }

    /**
     * A wrapped heading reprinted at the top of page 2 merges the same way it did on page 1, so it
     * produces the same header signature and is recognized as `REPEATED_HEADER` rather than
     * opening a second section.
     *
     * <p>This was the one exposure left open when the capability was written -- the merge could in
     * principle succeed on one page and not the next, and a locator that splits a table at a page
     * boundary loses the second half's balance chain. Cheap to construct, so it is pinned rather
     * than assumed. What remains unverified is the ASYMMETRIC case, where a bank reprints only
     * part of a wrapped heading on later pages; no committed document does that, and no behaviour
     * is guaranteed for it.
     */
    @Test
    void aWrappedHeadingReprintedOnTheNextPageIsTheSameTable() {
        List<PositionedText> runs = new java.util.ArrayList<>();
        for (int page = 0; page <= 1; page++) {
            runs.addAll(List.of(
                    run("Txn", 50f, 15f, 100f, page), run("Transaction", 150f, 55f, 100f, page),
                    run("Withdrawal", 300f, 52f, 100f, page), run("Closing", 430f, 36f, 100f, page),
                    run("Date", 52f, 20f, 108f, page), run("Details", 155f, 33f, 108f, page),
                    run("Amount", 305f, 33f, 108f, page), run("Balance", 433f, 36f, 108f, page)));
            runs.addAll(List.of(
                    run("1" + page + "/01/2026", 50f, 45f, 140f, page), run("UPI PAYMENT", 150f, 58f, 140f, page),
                    run("1,250.00", 300f, 38f, 140f, page), run("8,750.00", 430f, 38f, 140f, page),
                    run("1" + page + "/02/2026", 50f, 45f, 160f, page), run("CARD PAYMENT", 150f, 62f, 160f, page),
                    run("2,000.00", 300f, 38f, 160f, page), run("6,750.00", 430f, 38f, 160f, page)));
        }

        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting("capability").contains("WRAPPED_HEADER", "REPEATED_HEADER");
        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows())
                .as("all four transactions in one table, not two tables of two")
                .hasSize(4);
    }

    /**
     * A page footer directly above a heading is not the heading's upper half.
     *
     * <p>Found in review of this capability, and reachable rather than theoretical. The guard that
     * refuses to absorb a banner, footer or closing marker was asked only of the line being
     * ABSORBED, never of the line doing the absorbing -- so a footer could seed the columns. With
     * a table whose columns sit close enough together (34pt here), "Page 1 of 5" extracting as two
     * runs swallowed all three heading cells into its own two columns.
     *
     * <p>The damage is worse than a bad column name: the table came out with "Page Date" and
     * "1 of 5 Amount Balance", putting the amount and the balance in ONE cell. A mislabelled
     * column can be read; two values merged into one cell have lost one of them.
     *
     * <p>Fixture note (P-001 Fix A): "Amount" was originally given a width of 30, which left its
     * right edge 2pt short of "Balance" -- the same gap a single space occupies at this font size,
     * and therefore indistinguishable from one cell split into two runs, which is exactly what the
     * horizontal run-join in {@code PdfTableLocator.coalesceHeaderRuns} now reunites. The
     * fabricated geometry was also self-inconsistent: the data value under "Amount" was 38 wide
     * and so overran the "Balance" heading's own anchor by 6pt, which no real statement does. The
     * width is now 23, leaving a 9pt gap. Only that one number changed -- every x, every y and the
     * 34pt column pitch this test is actually about are untouched. For reference, the smallest
     * inter-column gap on any accepted header row in the real committed corpus is 13.38pt.
     */
    @Test
    void aPageFooterAboveAHeadingIsNotAbsorbedIntoIt() {
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                run("Page", 50f, 22f, 100f), run("1 of 5", 80f, 26f, 100f),

                run("Date", 52f, 20f, 108f), run("Amount", 86f, 23f, 108f),
                run("Balance", 118f, 33f, 108f),

                run("12/01/2026", 50f, 45f, 130f), run("1,250.00", 86f, 38f, 130f),
                run("8,750.00", 118f, 38f, 130f)));

        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting("capability").doesNotContain("WRAPPED_HEADER");
        assertThat(doc.sections().get(0).rows().get(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Date", "12/01/2026", "Amount", "1,250.00", "Balance", "8,750.00"));
    }

    /**
     * A blank run inside a heading line must not survive into the column's name.
     *
     * <p>Also found in review. PDFBox emits blank runs, and folding one into a merged cell puts a
     * DOUBLE space in the name -- "Txn  Date", which is visually identical to "Txn Date" and is
     * not the same string. Per-word matching (`isDateColumn`) still recognises it, so the table
     * looks entirely healthy: it locates, buckets and anchors correctly. But every whole-cell
     * lookup misses, and `CsvParser.firstNonBlank` is how `TransactionNormalizer` finds the date
     * and amount columns downstream. The column would carry the right values to a stage that
     * cannot find it.
     */
    @Test
    void aBlankRunInAHeadingLineDoesNotDoubleSpaceTheColumnName() {
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                run("Txn", 50f, 15f, 100f), run("Transaction", 150f, 55f, 100f),
                run("Withdrawal", 300f, 52f, 100f), run("Closing", 430f, 36f, 100f),

                run("  ", 51f, 4f, 108f), run("Date", 56f, 20f, 108f),
                run("Details", 155f, 33f, 108f),
                run("Amount", 305f, 33f, 108f), run("Balance", 433f, 36f, 108f),

                run("12/01/2026", 50f, 45f, 140f), run("UPI PAYMENT", 150f, 58f, 140f),
                run("1,250.00", 300f, 38f, 140f), run("8,750.00", 430f, 38f, 140f)));

        Map<String, String> row = new PdfTableLocator().locateAll(runs, null)
                .sections().get(0).rows().get(0);

        assertThat(row.keySet()).containsExactly(
                "Txn Date", "Transaction Details", "Withdrawal Amount", "Closing Balance");
        // The lookup that actually breaks: whole-cell, and the one TransactionNormalizer uses.
        assertThat(CsvParser.firstNonBlank(row, "txn date"))
                .as("the date column has to be findable by name, not just correctly filled")
                .isEqualTo("12/01/2026");
    }

    /**
     * FORMERLY an acknowledged limitation (see git history for the original pinned test this one
     * replaces), now resolved as a side effect of {@code dropCompletelyEmptySections} -- a general,
     * structural cleanup pass ("a section that collected literally nothing can never represent real
     * content"), not a fix specific to this shape.
     *
     * <p>A two-line block of pure labels — a summary panel reading "Opening Balance | Debit Amount
     * | Credit Amount | Closing Balance" over "as on Date | Total | Net | Carried" — satisfies every
     * signal a wrapped heading has: dateless, numberless, tightly spaced, and column-aligned. Merged,
     * it scores as a header, closes the transaction table above it, and opens a section that never
     * receives a row -- exactly as it always did. What changed is what happens to that phantom
     * section afterward: since it collects zero rows AND zero auxiliary text, the final cleanup pass
     * now drops it rather than surfacing it as an empty account for review.
     *
     * <p>The originally-recorded, ABANDONED attempt at a narrower fix is still worth keeping in mind
     * for anyone touching this area again: requiring a merged heading to be followed by a row that
     * reads as data under it rejects this block correctly but ALSO rejects the real fixed-deposit
     * schedule, because that table's amounts mis-bucket into its date column, so no row within any
     * sane lookahead yields a parseable date. {@code dropCompletelyEmptySections} sidesteps that
     * trap entirely by not asking the semantic question at all -- it only asks whether anything was
     * collected, which is a fact, not an inference.
     */
    @Test
    void emptyTwoLineLabelBlockSectionIsDroppedRatherThanSurfacedAsAPhantomAccount() {
        List<PositionedText> runs = new java.util.ArrayList<>(List.of(
                run("Txn Date", 50f, 33f, 100f), run("Narration", 150f, 36f, 100f),
                run("Withdrawals", 300f, 47f, 100f), run("Closing Balance", 430f, 62f, 100f),

                run("12/01/2026", 50f, 45f, 120f), run("UPI PAYMENT", 150f, 58f, 120f),
                run("1,250.00", 300f, 38f, 120f), run("8,750.00", 430f, 38f, 120f),
                run("14/01/2026", 50f, 45f, 140f), run("CARD PAYMENT", 150f, 62f, 140f),
                run("2,000.00", 300f, 38f, 140f), run("6,750.00", 430f, 38f, 140f)));

        runs.addAll(List.of(
                run("Opening Balance", 50f, 62f, 200f), run("Debit Amount", 150f, 52f, 200f),
                run("Credit Amount", 300f, 55f, 200f), run("Closing Balance", 430f, 62f, 200f),
                run("as on Date", 52f, 42f, 209f), run("Total", 152f, 22f, 209f),
                run("Net", 302f, 16f, 209f), run("Carried", 432f, 30f, 209f)));

        DocumentContext ctx = new DocumentContext("PDF", "WrappedHeaderPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("EMPTY_SECTION_DROPPED");
        assertThat(doc.sections())
                .as("the empty phantom section is dropped -- only the real table remains")
                .hasSize(1);
        assertThat(doc.sections().get(0).rows())
                .as("the real table keeps every one of its transactions")
                .hasSize(2);
    }

    @Test
    void aCaptionAboveTheTableIsNotAbsorbedIntoTheHeading() throws Exception {
        // The fixture prints "FD DETAILS :- FOR CURRENT ACCOUNT HOLDER" one line above the heading,
        // 10pt away and carrying neither a date nor a number -- by proximity alone, exactly the
        // shape of a wrapped heading's upper line. What separates them is structural: a caption
        // does not sit inside the columns below it. Merged in, it would take the header's leftmost
        // anchor with it and shift every column.
        assertThat(locate(null).sections().get(0).auxiliaryText())
                .anySatisfy(line -> assertThat(line).contains("FD DETAILS"));
    }
}
