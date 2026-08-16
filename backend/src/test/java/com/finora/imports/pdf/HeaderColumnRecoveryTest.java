package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BLANK_COLUMN_NAME_QUALIFIED / RECOVERED_MISSING_DESCRIPTION_COLUMN /
 * RECOVERED_MISSING_SERIAL_NUMBER_COLUMN: a header printed in three stacked tiers, where the
 * accepted (bottom, data-adjacent) tier alone is missing a narration column entirely and names its
 * Balance column with a bare currency unit -- found on a real ICICI savings e-statement. See
 * PdfTableLocator.resolveBlankColumnNames/recoverMissingDescriptionColumn/
 * recoverMissingSerialNumberColumn for the full mechanism and why each is scoped as narrowly as it
 * is.
 *
 * <p>Every fixture below is fully hand-synthesized -- invented column geometry, dates, amounts,
 * and narrations -- per the Synthetic Fixture Policy; no value from the real document appears
 * here. Relative column spacing mirrors the real statement's own tier structure (needed to
 * reproduce the exact refusal/recovery shape being tested), not any of its actual measurements.
 */
class HeaderColumnRecoveryTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** The three-tier header: a top qualifier tier, a middle tier mergeHeaderLines correctly
     *  refuses to fold in (its "Ref No" cell sits past HEADER_WRAP_MAX_COLUMN_JOIN from anything
     *  below), and the accepted bottom tier -- exactly ICICI's real shape, genericized. y
     *  INCREASES going down the page in groupIntoRows' sort convention (ascending -- verified
     *  against the real document's own geometry), so the top tier gets the SMALLEST y here. */
    private static List<PositionedText> threeTierHeader(float y) {
        List<PositionedText> header = new ArrayList<>();
        float topY = y;
        float midY = y + 6f;
        float bottomY = y + 12f;
        // Top tier: qualifiers for the two duplicate "Amount" cells and the blank Balance cell.
        // Each sits within HEADER_WRAP_MAX_COLUMN_JOIN (40pt) of its own bottom-tier anchor.
        header.add(run("Withdrawal", 342f, 60f, topY));
        header.add(run("Deposit", 432f, 45f, topY));
        header.add(run("Balance", 522f, 40f, topY));
        // Middle tier: refused wholesale (Ref No sits 75pt from Date, past the 40pt join
        // tolerance) -- but still a genuine multi-cell tier, unlike the lone-caption test below.
        header.add(run("Sr No.", 20f, 20f, midY));
        header.add(run("Ref No", 145f, 40f, midY));
        header.add(run("Particulars", 220f, 70f, midY));
        // Bottom tier: the accepted header line, adjacent to data. Gaps between adjacent cells
        // are kept comfortably above HEADER_RUN_JOIN_MAX_GAP (6pt) so coalesceHeaderRuns -- which
        // exists to rejoin one label PDFBox split into several runs -- does not mistake these for
        // split fragments of ONE cell and merge them into each other before recovery even runs.
        header.add(run("Date", 70f, 30f, bottomY));
        header.add(run("Amount (Rs.)", 340f, 70f, bottomY));
        header.add(run("Amount (Rs.)", 430f, 70f, bottomY));
        header.add(run("(Rs.)", 520f, 25f, bottomY));
        return header;
    }

    private static List<PositionedText> dataRow(String srNo, String date, String particulars,
            String withdrawal, String deposit, String balance, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(srNo, 25f, 10f, y));
        row.add(run(date, 71f, 42f, y));
        row.add(run(particulars, 225f, particulars.length() * 5.2f, y));
        if (!withdrawal.isEmpty()) row.add(run(withdrawal, 345f, 26f, y));
        if (!deposit.isEmpty()) row.add(run(deposit, 435f, 26f, y));
        row.add(run(balance, 522f, 31f, y));
        return row;
    }

    @Test
    void threeTierHeader_recoversAllThreeMissingOrBlankColumns_andBucketsDataCorrectly() {
        List<PositionedText> positioned = new ArrayList<>(threeTierHeader(400f));
        positioned.addAll(dataRow("1", "01.02.2026", "Merchant One", "270.00", "", "2220.04", 420f));
        positioned.addAll(dataRow("2", "02.02.2026", "Merchant Two", "160.00", "", "2060.04", 440f));
        positioned.addAll(dataRow("3", "03.02.2026", "Salary Credit", "", "5000.00", "7060.04", 460f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(3);

        // Date bucketed cleanly -- not corrupted by Sr No.'s own digit values (the real ICICI bug:
        // no anchor for Sr No. meant its digits landed nearest to Date and got glued onto it).
        assertThat(rows.get(0)).containsEntry("Date", "01.02.2026");
        assertThat(rows.get(0)).containsEntry("Particulars", "Merchant One");
        assertThat(rows.get(0)).containsEntry("Withdrawal Amount (Rs.)", "270.00");
        assertThat(rows.get(0)).containsEntry("Balance (Rs.)", "2220.04");
        assertThat(rows.get(2)).containsEntry("Deposit Amount (Rs.)", "5000.00");

        List<String> fired = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(fired).contains(
                "BLANK_COLUMN_NAME_QUALIFIED",
                "RECOVERED_MISSING_DESCRIPTION_COLUMN",
                "RECOVERED_MISSING_SERIAL_NUMBER_COLUMN");
    }

    /**
     * Regression guard for the real bug found on a real SBI credit-card statement while building
     * this mechanism: a lone, single-cell caption line (no data value, otherwise indistinguishable
     * from a genuine header-tier label) sitting one line above an unrelated header must NOT be
     * admitted as a new column -- only a line with more than one cell counts as a real tier. The
     * caption here uses "Particulars" specifically, mirroring the real collision (a rejected
     * block's own caption label lexically matched the narration vocabulary).
     */
    @Test
    void loneSingleCellCaptionLine_isNotRecoveredAsAColumn() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Particulars", 180f, 60f, 400f)); // lone cell, own line, nothing else near it
        positioned.add(run("Date", 74f, 25f, 420f));
        positioned.add(run("Amount", 400f, 40f, 420f));
        positioned.add(run("01.02.2026", 61f, 42f, 440f));
        positioned.add(run("500.00", 400f, 30f, 440f));
        positioned.add(run("02.02.2026", 61f, 42f, 460f));
        positioned.add(run("600.00", 400f, 30f, 460f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("RECOVERED_MISSING_DESCRIPTION_COLUMN");
    }
}
