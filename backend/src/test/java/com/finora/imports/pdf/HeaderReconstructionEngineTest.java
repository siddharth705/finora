package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEADER_RECONSTRUCTED: the Phase 2E.2 prototype of the Header Quality Gate + Header
 * Reconstruction Engine (docs/architecture/system-design/header-reconstruction-design.md).
 *
 * <p>Every fixture below is fully hand-synthesized -- invented column geometry, dates, amounts,
 * and narrations -- per the Synthetic Fixture Policy. The SBI-shaped fixture mirrors the real
 * document's coordinate SHAPE (verified against the committed sbi-credit-card-statement.trace and
 * the Phase 2E.1 investigation), not any of its actual values -- the real trace itself does not
 * exercise this capability (see CapabilityCorpusCoverageTest's DECLARED_WITHOUT_A_TRACE entry for
 * why: its own redacted dates are not parseable, so this engine's own validation correctly
 * declines to prefer a reconstruction it cannot verify helps).
 */
class HeaderReconstructionEngineTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /**
     * The real shape found on SBI Credit Card.PDF's supplementary-cardholder section: the header's
     * middle column name ("Transaction Details") sits ALONE on the physical line above the line
     * that actually gets accepted as the header ("Date | Amount"), so {@link PdfTableLocator}'s
     * ordinary forward-only wrap ({@code wrappedHeaderAt}) never finds it -- it only ever looks
     * downward from the row it seeds on. Recovered here by composing the accepted row with the
     * physical line immediately above it, validated by actually bucketing the section's own real
     * rows and confirming the recovered Description column takes real narration rather than losing
     * it into whichever of Date/Amount happens to sit nearest.
     */
    @Test
    void sbiShapedPartitionedHeader_recoversDescriptionColumnFromTheLineAbove() {
        List<PositionedText> positioned = new ArrayList<>();
        // Line 1 (y=100): the OTHER column's name, alone -- never scores as a header by itself
        // (no date hint), so wrappedHeaderAt's forward wrap from THIS line is what runs, and it
        // refuses (the row below carries its own two column names, joining no single column here).
        positioned.add(run("Transaction Details", 180f, 90f, 100f));
        // Line 2 (y=108, 8pt gap -- comfortably inside HEADER_WRAP_MAX_GAP): scores alone
        // (date hint + one recognized name), so THIS is what gets accepted -- and its own forward
        // wrap attempt (onto the first real data row) refuses too, since that row carries a date.
        positioned.add(run("Date", 40f, 30f, 108f));
        positioned.add(run("Amount", 380f, 45f, 108f));
        positioned.add(run("( Rs )", 428f, 30f, 109f));
        // Real rows: date, a real narration, and an amount -- three distinct values the accepted
        // 2-column header cannot hold without concatenating two of them into one cell.
        positioned.addAll(dataRow("01 Aug 26", "SAMPLE ONLINE STORE PURCHASE", "1,250.00", 120f));
        positioned.addAll(dataRow("03 Aug 26", "SAMPLE UTILITY BILL PAYMENT", "980.50", 128f));
        positioned.addAll(dataRow("05 Aug 26", "SAMPLE GROCERY STORE PURCHASE", "540.00", 136f));
        positioned.addAll(dataRow("07 Aug 26", "SAMPLE SUBSCRIPTION RENEWAL", "299.00", 144f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsEntry("Date", "01 Aug 26");
        assertThat(rows.get(0)).containsEntry("Transaction Details", "SAMPLE ONLINE STORE PURCHASE");
        assertThat(rows.get(0)).containsEntry("Amount ( Rs )", "1,250.00");
        assertThat(rows.get(3)).containsEntry("Transaction Details", "SAMPLE SUBSCRIPTION RENEWAL");

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("HEADER_RECONSTRUCTED");
        assertThat(doc.sections().get(0).evidence().headerReconstructionFindings())
                .as("recovered -- no lingering uncertainty finding for a section that now stages cleanly")
                .isEmpty();
    }

    private static List<PositionedText> dataRow(String date, String description, String amount, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 40f, 45f, y));
        row.add(run(description, 180f, description.length() * 5.0f, y));
        row.add(run(amount, 400f, 40f, y));
        return row;
    }

    /**
     * The real shape found on Statement.pdf/IOB: the tier immediately above the accepted header is
     * not a single orphaned cell (SBI's shape, above) but a genuine multi-cell tier -- four column
     * names, all sitting far enough from the accepted line's own two anchors that none of them could
     * be mistaken for renaming an existing column. Phase 2E.2's prototype declined this shape outright
     * ({@code nonBlankCount(above) != 1}); this is the fill-empty generalization design doc §9.3
     * describes, mirroring IOB's real coordinate geometry (traced via a throwaway reflection probe
     * against the real document, not reproduced here) -- not its actual values.
     */
    @Test
    void multiCellPartitionedTierImmediatelyAbove_recoversAllFourColumns() {
        List<PositionedText> positioned = new ArrayList<>();
        // Tier above (y=192): four column names, none within HEADER_WRAP_MAX_COLUMN_JOIN of either
        // of the accepted line's two anchors (40, 500) -- every one is a genuine fill-empty addition.
        positioned.add(run("Particulars", 150f, 80f, 192f));
        positioned.add(run("Debit", 280f, 40f, 192f));
        positioned.add(run("Credit", 350f, 40f, 192f));
        positioned.add(run("Balance", 420f, 50f, 192f));
        // Accepted line (y=200, 8pt gap -- inside HEADER_WRAP_MAX_GAP): scores alone (date hint +
        // one other recognized name, "type" -- matching real IOB's own accepted row, which also
        // recovers via "Type") but explains almost none of the real rows below -- weak by row
        // compatibility, the same signal SBI's case is weak by.
        positioned.add(run("Date", 40f, 30f, 200f));
        positioned.add(run("Type", 500f, 50f, 200f));
        positioned.addAll(iobRow("01 Aug 26", "SAMPLE ONLINE PURCHASE", "500.00", "", "9,500.00", "UPI", 212f));
        positioned.addAll(iobRow("03 Aug 26", "SAMPLE SALARY CREDIT", "", "10,000.00", "19,500.00", "NEFT", 220f));
        positioned.addAll(iobRow("05 Aug 26", "SAMPLE UTILITY PAYMENT", "1,200.00", "", "18,300.00", "UPI", 228f));
        positioned.addAll(iobRow("07 Aug 26", "SAMPLE RENT PAYMENT", "6,000.00", "", "12,300.00", "UPI", 236f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsEntry("Date", "01 Aug 26");
        assertThat(rows.get(0)).containsEntry("Particulars", "SAMPLE ONLINE PURCHASE");
        assertThat(rows.get(0)).containsEntry("Debit", "500.00");
        assertThat(rows.get(0)).containsEntry("Balance", "9,500.00");
        assertThat(rows.get(0)).containsEntry("Type", "UPI");
        assertThat(rows.get(1)).containsEntry("Credit", "10,000.00");

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("HEADER_RECONSTRUCTED");
    }

    /**
     * Regression guard for a real bug found in self-review while building buildHeaderColumns'
     * containsEmbeddedDateRange guard (Phase 2E.5, HSBC row-formation fix): buildHeaderColumns can
     * run TWICE for the same physical header row -- once on the row as originally accepted, and
     * again on {@code reconstructHeader}'s candidate when the first attempt is judged weak enough
     * to trigger reconstruction. reconstructHeader's own candidate always starts from every
     * non-blank fragment of the ORIGINAL header row verbatim, so an orphaned caption sitting on
     * that row (chain-based clustering can now fold one onto it, the same mechanism
     * OrphanedHeaderRowCaptionTest exercises) gets re-scanned by containsEmbeddedDateRange on the
     * second call too -- and without a fix, appended to the section's auxiliary text a second time.
     * Same header shape as multiCellPartitionedTierImmediatelyAbove_recoversAllFourColumns above
     * (proven to trigger a successful reconstruction), with an orphaned caption added onto the
     * accepted line's own physical row.
     */
    @Test
    void orphanedCaptionOnAReconstructedHeadersOwnRow_isNotDuplicatedIntoAuxiliaryText() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Particulars", 150f, 80f, 192f));
        positioned.add(run("Debit", 280f, 40f, 192f));
        positioned.add(run("Credit", 350f, 40f, 192f));
        positioned.add(run("Balance", 420f, 50f, 192f));
        positioned.add(run("Date", 40f, 30f, 200f));
        positioned.add(run("Type", 500f, 50f, 200f));
        // The orphaned caption: 1.5pt below the accepted line, well within groupIntoRows'
        // chain-based ROW_Y_TOLERANCE, so it folds onto the SAME physical row as Date/Type above --
        // exactly the shape OrphanedHeaderRowCaptionTest reproduces, just now also feeding a header
        // that goes on to trigger reconstruction. x=600 deliberately clear of every tier-above
        // anchor (150/280/350/420) by more than HEADER_WRAP_MAX_COLUMN_JOIN (40pt) -- reconstructHeader's
        // own candidate starts from every non-blank cell of this row verbatim (unfiltered by
        // buildHeaderColumns' containsEmbeddedDateRange, which only runs inside buildHeaderColumns
        // itself), so a caption placed too close to one of those anchors would make reconstructHeader
        // decline the tier above as a false rename-conflict -- a fixture-placement pitfall, not the
        // duplication bug this test targets.
        positioned.add(run("for Statement Period: 01 Jun 26 to 30 Jun 26", 600f, 220f, 201.5f));
        positioned.addAll(iobRow("01 Aug 26", "SAMPLE ONLINE PURCHASE", "500.00", "", "9,500.00", "UPI", 212f));
        positioned.addAll(iobRow("03 Aug 26", "SAMPLE SALARY CREDIT", "", "10,000.00", "19,500.00", "NEFT", 220f));
        positioned.addAll(iobRow("05 Aug 26", "SAMPLE UTILITY PAYMENT", "1,200.00", "", "18,300.00", "UPI", 228f));
        positioned.addAll(iobRow("07 Aug 26", "SAMPLE RENT PAYMENT", "6,000.00", "", "12,300.00", "UPI", 236f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        PdfTableLocator.LocatedSection section = doc.sections().get(0);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .as("reconstruction still succeeds -- this is a duplication check, not a regression "
                        + "of the reconstruction itself")
                .contains("HEADER_RECONSTRUCTED");
        assertThat(section.auxiliaryText())
                .as("the orphaned caption's text appears exactly once, not once per buildHeaderColumns "
                        + "call")
                .filteredOn(line -> line.contains("for Statement Period: 01 Jun 26 to 30 Jun 26"))
                .hasSize(1);
    }

    private static List<PositionedText> iobRow(String date, String particulars, String debit,
            String credit, String balance, String refNo, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 40f, 45f, y));
        row.add(run(particulars, 150f, particulars.length() * 5.0f, y));
        if (!debit.isEmpty()) row.add(run(debit, 280f, 40f, y));
        if (!credit.isEmpty()) row.add(run(credit, 350f, 40f, y));
        row.add(run(balance, 420f, 50f, y));
        row.add(run(refNo, 500f, 50f, y));
        return row;
    }

    /**
     * A different bank flavor of the same general shape (§2 of the design doc: "columns
     * PARTITIONED, not refined, across physical lines") -- a savings-style statement whose Balance
     * column name sits alone one line ABOVE a "Date | Narration" line that scores alone. Proves the
     * engine is general, not an SBI-specific pattern: nothing here shares SBI's column names,
     * coordinates, or vocabulary.
     */
    @Test
    void differentBankShapedPartitionedHeader_alsoRecovers() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Closing Balance", 480f, 80f, 200f));
        positioned.add(run("Date", 35f, 30f, 207f));
        positioned.add(run("Narration", 150f, 60f, 207f));
        positioned.addAll(savingsRow("02 Jul 26", "SAMPLE SALARY CREDIT", "45,000.00", 220f));
        positioned.addAll(savingsRow("04 Jul 26", "SAMPLE RENT PAYMENT", "18,500.00", 228f));
        positioned.addAll(savingsRow("09 Jul 26", "SAMPLE INSURANCE PREMIUM", "2,340.00", 236f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("Date", "02 Jul 26");
        assertThat(rows.get(0)).containsEntry("Narration", "SAMPLE SALARY CREDIT");
        assertThat(rows.get(0)).containsEntry("Closing Balance", "45,000.00");

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("HEADER_RECONSTRUCTED");
    }

    private static List<PositionedText> savingsRow(String date, String narration, String balance, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 35f, 30f, y));
        row.add(run(narration, 150f, narration.length() * 5.0f, y));
        row.add(run(balance, 480f, 45f, y));
        return row;
    }

    /**
     * The structural non-regression guarantee (design doc §7, regression corpus §"Structural
     * guarantee"): an ordinary, single-line, already-complete header must never even reach the
     * reconstruction engine, regardless of what happens to sit on the physical line above it in the
     * document (here, unrelated free-standing account-details text, exactly the kind of line real
     * statements print above their real header). HEADER_RECONSTRUCTED must not appear at all.
     */
    @Test
    void ordinaryCompleteHeader_neverInvokesTheEngine() {
        List<PositionedText> positioned = new ArrayList<>();
        // Unrelated auxiliary text one line above -- carries no date/number and isn't structural,
        // so it WOULD be a candidate fragment if the gate fired at all. It must never be reached.
        positioned.add(run("Branch Address", 40f, 70f, 100f));
        positioned.add(run("Date", 40f, 30f, 112f));
        positioned.add(run("Description", 150f, 60f, 112f));
        positioned.add(run("Debit", 350f, 35f, 112f));
        positioned.add(run("Credit", 420f, 35f, 112f));
        positioned.add(run("Balance", 490f, 40f, 112f));
        positioned.addAll(fullRow("01 Aug 26", "SAMPLE PAYMENT", "500.00", "", "10,000.00", 124f));
        positioned.addAll(fullRow("02 Aug 26", "SAMPLE DEPOSIT", "", "2,000.00", "12,000.00", 132f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .as("a header that already explains its own rows must never reach the reconstruction engine")
                .doesNotContain("HEADER_RECONSTRUCTED");
    }

    private static List<PositionedText> fullRow(String date, String description, String debit,
            String credit, String balance, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 40f, 30f, y));
        row.add(run(description, 150f, description.length() * 5.0f, y));
        if (!debit.isEmpty()) row.add(run(debit, 350f, 35f, y));
        if (!credit.isEmpty()) row.add(run(credit, 420f, 35f, y));
        row.add(run(balance, 490f, 45f, y));
        return row;
    }

    /**
     * Found via a full-corpus regression sweep after generalizing the engine to multi-cell tiers
     * (the test above): a genuine prose caption or disclaimer line sitting one physical line above
     * a weak header -- carrying no date/number value and matching no structural-marker regex, so it
     * passes every guard the multi-cell walk already had -- was composed in as a brand-new header
     * column named after the whole sentence, corrupting every row's real value into that garbage
     * column. {@code findQualifyingLabel} and {@code recoverMissingSerialNumberColumn} already guard
     * against exactly this shape via {@code hasProseLengthCell} (real SBI/ICICI/AU regressions their
     * own doc comments describe); this engine's backward walk needs the identical guard.
     */
    @Test
    void proseCaptionLineImmediatelyAbove_isNeverComposedAsAColumn() {
        List<PositionedText> positioned = new ArrayList<>();
        // A long disclaimer sentence -- carries no date/number, matches no SECTION_MARKER/
        // PAGE_FOOTER/STATEMENT_CLOSING_MARKER pattern, and sits well clear of the accepted line's
        // two anchors (40, 500) -- passing every guard the walk had before this fix.
        positioned.add(run("Please retain this statement for your records and report any discrepancies "
                + "within thirty days of the statement date to avoid forfeiting your claim", 150f, 300f, 192f));
        positioned.add(run("Date", 40f, 30f, 200f));
        positioned.add(run("Type", 500f, 50f, 200f));
        positioned.addAll(iobRow("01 Aug 26", "SAMPLE ONLINE PURCHASE", "500.00", "", "9,500.00", "UPI", 212f));
        positioned.addAll(iobRow("03 Aug 26", "SAMPLE SALARY CREDIT", "", "10,000.00", "19,500.00", "NEFT", 220f));
        positioned.addAll(iobRow("05 Aug 26", "SAMPLE UTILITY PAYMENT", "1,200.00", "", "18,300.00", "UPI", 228f));
        positioned.addAll(iobRow("07 Aug 26", "SAMPLE RENT PAYMENT", "6,000.00", "", "12,300.00", "UPI", 236f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .as("nothing safely composable above a prose-only line -- the engine must decline, "
                        + "not compose the sentence in as a column")
                .doesNotContain("HEADER_RECONSTRUCTED");
        for (Map<String, String> row : doc.sections().get(0).rows()) {
            assertThat(row.keySet())
                    .as("no composed column name may contain the disclaimer's own text")
                    .noneMatch(name -> name.contains("retain this statement"));
        }
    }

    /**
     * The safety property found while building this engine, on the real SBI document: two sections
     * whose TRUE headers are identical in column names (a real, legitimate shape -- the same
     * statement template printed once per cardholder) must never be silently merged into one
     * section just because reconstruction happens to recover a header matching the one already
     * open. Reproduced here with two independent, correctly-partitioned sections sharing one true
     * shape, separated by an explicit new-account banner (the same kind of signal a real composite
     * statement uses). Both sections must recover their OWN rows, never merge into one.
     */
    @Test
    void twoSectionsWithTheSameTrueHeaderShape_recoverSeparatelyRatherThanMerge() {
        List<PositionedText> positioned = new ArrayList<>();
        // Section A's partitioned header + two rows.
        positioned.add(run("Transaction Details", 180f, 90f, 100f));
        positioned.add(run("Date", 40f, 30f, 108f));
        positioned.add(run("Amount", 380f, 45f, 108f));
        positioned.addAll(dataRow("01 Aug 26", "SAMPLE STORE PURCHASE ONE", "111.00", 120f));
        positioned.addAll(dataRow("02 Aug 26", "SAMPLE STORE PURCHASE TWO", "222.00", 128f));
        // An explicit new-account banner between the two sections, the same shape
        // PdfTableLocator.SECTION_MARKER already recognizes on real composite statements.
        positioned.add(run("CREDIT CARD ACCOUNT - 5678901234567890", 40f, 220f, 300f)); // synthetic-ok: invented, not corpus-derived
        // Section B's partitioned header, IDENTICAL true shape to Section A's, + two DIFFERENT rows.
        positioned.add(run("Transaction Details", 180f, 90f, 320f));
        positioned.add(run("Date", 40f, 30f, 328f));
        positioned.add(run("Amount", 380f, 45f, 328f));
        positioned.addAll(dataRow("05 Aug 26", "SAMPLE STORE PURCHASE THREE", "333.00", 340f));
        positioned.addAll(dataRow("06 Aug 26", "SAMPLE STORE PURCHASE FOUR", "444.00", 348f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections())
                .as("two financially distinct sections sharing one true header shape must stay separate")
                .hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(1).rows()).hasSize(2);
        assertThat(doc.sections().get(0).rows().get(0)).containsEntry("Transaction Details", "SAMPLE STORE PURCHASE ONE");
        assertThat(doc.sections().get(1).rows().get(0)).containsEntry("Transaction Details", "SAMPLE STORE PURCHASE THREE");
    }
}
