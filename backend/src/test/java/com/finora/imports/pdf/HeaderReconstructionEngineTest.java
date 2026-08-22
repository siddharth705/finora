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
