package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A gap in {@code pendingLeading} found while investigating a real ICICI savings statement: when a
 * section's table has no "Opening Balance" (or similar) summary row at all -- the very first
 * bucketed line after the header is itself dateless, narration-only leading content for the FIRST
 * transaction -- it was wrongly treated as a free-standing row instead of buffered as leading
 * narration. The {@code currentRows.isEmpty()} branch's own doc comment already anticipated a
 * genuine value-bearing summary row (e.g. "Opening Balance: 10000.00") standing alone correctly;
 * it did not distinguish that case from a narration-only line with nothing to attach to YET, which
 * is exactly {@link LeadingNarrationContinuationPdfPreviewGeneratorTest}'s own shape, just without
 * that fixture's Opening Balance row absorbing the difference. Every fixture below is fully
 * hand-synthesized per the Synthetic Fixture Policy; no value from any real document appears here.
 */
class LeadingNarrationBeforeFirstAnchorPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    @Test
    void narrationOnlyLine_immediatelyAfterHeader_withNoOpeningBalanceRow_attachesToTheFirstTransaction() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f));
        positioned.add(run("Particulars", 150f, 80f, 100f));
        positioned.add(run("Debit", 350f, 40f, 100f));
        positioned.add(run("Balance", 480f, 50f, 100f));
        // No Opening Balance row here -- the very first bucketed content is this narration-only
        // line, with nothing yet in currentRows to attach it to.
        positioned.add(run("SAMPLE ONLINE STORE PURCHASE", 150f, 150f, 110f));
        positioned.add(run("01 Aug 26", 40f, 45f, 118f));
        positioned.add(run("500.00", 350f, 40f, 118f));
        positioned.add(run("9,500.00", 480f, 50f, 118f));
        positioned.add(run("03 Aug 26", 40f, 45f, 130f));
        positioned.add(run("200.00", 350f, 40f, 130f));
        positioned.add(run("9,300.00", 480f, 50f, 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the leading narration must attach to transaction 1, not stand alone as its own row")
                .hasSize(2);
        assertThat(rows.get(0)).containsEntry("Date", "01 Aug 26");
        assertThat(rows.get(0).get("Particulars")).contains("SAMPLE ONLINE STORE PURCHASE");
    }

    /**
     * Found while regression-testing the fix above against the full real corpus: with TWO (or more)
     * consecutive narration-only lines before the first anchor, the first one's own leading-
     * narration processing sets {@code lastRowPage}/{@code lastRowY} (the same bookkeeping the
     * ordinary leading-narration branch always does) -- which makes the SECOND line's {@code
     * samePage} check true, even though {@code currentRows} is still genuinely empty. The trailing-
     * continuation branch does not itself guard on that, and crashed with an
     * {@code IndexOutOfBoundsException} calling {@code currentRows.get(currentRows.size() - 1)} on
     * an empty list.
     */
    @Test
    void twoConsecutiveNarrationOnlyLines_beforeTheFirstAnchor_doNotCrash() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f));
        positioned.add(run("Particulars", 150f, 80f, 100f));
        positioned.add(run("Debit", 350f, 40f, 100f));
        positioned.add(run("Balance", 480f, 50f, 100f));
        positioned.add(run("UPI/SAMPLE REF/SAMPLE MERCHANT", 150f, 150f, 110f));
        positioned.add(run("SAMPLE ONLINE STORE PURCHASE", 150f, 150f, 118f));
        positioned.add(run("01 Aug 26", 40f, 45f, 126f));
        positioned.add(run("500.00", 350f, 40f, 126f));
        positioned.add(run("9,500.00", 480f, 50f, 126f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Date", "01 Aug 26");
        assertThat(rows.get(0).get("Particulars"))
                .contains("UPI/SAMPLE REF/SAMPLE MERCHANT")
                .contains("SAMPLE ONLINE STORE PURCHASE");
    }

    /**
     * Found while regression-testing the fix above against the full real corpus (SBI Card): {@code
     * isNarrationOnly} judges a row narration-only by checking whether any BUCKETED value parses as
     * a number -- correct once a header has a real column for every value a row carries, but a real
     * SBI section's header is missing a narration/description column entirely (only "Date" and
     * "Amount" survive), so a row's merchant text has nowhere to go and squishes into "Date" with
     * the date itself, and its amount prints with a trailing single-letter Cr/Dr marker
     * ("500.00 X") that defeats {@code CsvParser.parseNumeric}'s strict match. Both bucketed values
     * then fail the number check even though the row plainly carries a real transaction, and the
     * {@code currentRows.isEmpty()} gate wrongly deferred it as leading narration instead of letting
     * it stand alone -- corrupting an already-weak but previously-stable section.
     */
    @Test
    void firstRowUnderAHeaderMissingItsNarrationColumn_stillStandsAloneDespiteSquishedBucketing() {
        List<PositionedText> positioned = new ArrayList<>();
        // A real, weak header shape: "Date" and "Amount" only -- no narration/description column,
        // so merchant text has nowhere to anchor and lands in "Date" alongside the date itself.
        positioned.add(run("Date", 40f, 30f, 100f));
        positioned.add(run("Amount", 400f, 45f, 100f));
        // Trailing single-letter Cr/Dr marker on the amount -- real SBI shape -- defeats
        // CsvParser.parseNumeric's strict match on "Amount" too.
        positioned.add(run("01 Aug 26", 40f, 45f, 110f));
        positioned.add(run("SAMPLE MERCHANT PURCHASE", 150f, 150f, 110f));
        positioned.add(run("500.00 X", 400f, 45f, 110f));
        positioned.add(run("03 Aug 26", 40f, 45f, 120f));
        positioned.add(run("SAMPLE UTILITY PAYMENT", 150f, 150f, 120f));
        positioned.add(run("200.00 X", 400f, 45f, 120f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the section's own first real row, weakly bucketed or not, must stand on its "
                        + "own rather than being silently deferred as leading narration")
                .hasSize(2);
        // "SAMPLE MERCHANT PURCHASE" lands in "Amount" here, not "Date" -- bucketRow's own
        // date-already-has-a-value redirect (nearest to Date, but Date is already a clean parsed
        // date, so the next run goes to the next column over). The real SBI trace glues the date
        // and merchant into a single PDF text run instead, landing both in "Date" together; this
        // fixture's two separate runs exercise the same "the row still carries a real value"
        // question via a different, still-real bucketing path -- what matters here is that the row
        // stood alone at all, not which column absorbed which fragment.
        assertThat(rows.get(0).get("Date")).contains("01 Aug 26");
        assertThat(rows.get(0).get("Amount")).contains("SAMPLE MERCHANT PURCHASE");
    }

    /**
     * Found while regression-testing the fix above against the full real corpus (AU Credit Card): a
     * section whose header is perfectly well-formed can still carry NO transaction at all -- a real
     * EMI/interest-disclosure summary panel, all narration, nothing a real anchor ever claims before
     * the section closes. Deferring the first such line broke this differently than SBI's squished-
     * bucketing case: the first line's own leading-narration bookkeeping (lastRowPage/lastRowY) made
     * the SECOND line's samePage true, and with {@code trailingCountSinceLastAnchor} never having
     * been closed off (only the standalone branch does that), both later lines silently swallowed
     * into the SAME leading-narration buffer as the first -- collapsing two independent rows (one
     * standalone, one flushed) into a single combined one.
     */
    @Test
    void narrationOnlySection_withNoTransactionEverFollowing_firstLineStandsAloneNotSwallowedWhole() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f));
        positioned.add(run("Amount", 400f, 45f, 100f));
        // Three narration-only lines -- no date, no number -- with NOTHING real ever following in
        // this section. The first line must still stand alone as its own row, exactly as it did
        // before the leading-narration fix; only the second and third, which genuinely have nowhere
        // else to go, should flush together as leading narration once the section closes.
        positioned.add(run("Interest is charged from the transaction date until full payment is received",
                150f, 300f, 110f));
        positioned.add(run("Applicable rate varies by card type and is disclosed separately",
                150f, 300f, 120f));
        positioned.add(run("Total interest charged this cycle appears on the next statement",
                150f, 300f, 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("no real transaction ever follows in this section -- the first narration line "
                        + "must stand alone rather than being swallowed into one combined row with "
                        + "the rest")
                .hasSize(2);
    }

    /**
     * Found while regression-testing the fix above against the full real corpus (HDFC Credit
     * Card): a genuine two-line merchant-description caption ("merchant name" / "payment
     * reference"), printed loosely spaced (11pt) above its OWN tightly-spaced (4.3pt) pair with
     * the real transaction below, is single-column (so the ICICI Credit Card multi-column
     * exclusion above does not apply) and does have a real anchor within the lookahead window (so
     * the anchor-lookahead alone defers it too) -- yet, unlike the one real ICICI savings
     * statement this whole gate exists for, its first line does NOT belong to the transaction
     * below: baseline stands it alone, and this document's own regression-guard corpus test locks
     * that in. The distinguishing signal, found by comparing gaps: the ICICI savings narration's
     * gap to its anchor (5.1pt) matches the anchor's OWN established line pitch (its wrapped
     * continuation sits 5.0pt below it) almost exactly, while here the first line's 11pt gap to
     * the second is nothing like the second line's 4.3pt gap to the real anchor -- a genuine pitch
     * break, not one consistent, tightly-set block.
     */
    @Test
    void looselySpacedCaptionAboveATightlySpacedPairWithTheAnchor_standsAloneNotMergedIntoTheTransaction() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 169.5f, 30f, 691.6f));
        positioned.add(run("Description", 250.7f, 82.8f, 691.6f));
        positioned.add(run("Amount", 530.3f, 27.7f, 691.6f));
        // A loosely-spaced (11pt) two-line merchant caption, itself tightly-spaced (4.3pt) to the
        // real transaction below -- mirrors HDFC Credit Card's real geometry exactly (values
        // invented, coordinates match the shape that broke).
        positioned.add(run("SAMPLE MERCHANT NAME [MERCHANT ID: 12345]", 250.7f, 200f, 706.2f));
        positioned.add(run("SAMPLE PAYMENT REFERENCE (Ref#", 250.7f, 200f, 717.2f));
        positioned.add(run("30/06/2026", 169.5f, 60f, 721.5f));
        positioned.add(run("440.00", 530.3f, 40f, 721.5f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the loosely-spaced first caption line must stand alone -- it is not part of "
                        + "the tightly-spaced pair the real transaction anchors")
                .hasSize(2);
        assertThat(rows.get(0).get("Description")).contains("SAMPLE MERCHANT NAME");
        assertThat(rows.get(1).get("Description")).contains("SAMPLE PAYMENT REFERENCE");
        assertThat(rows.get(1)).containsEntry("Date", "30/06/2026");
    }
}
