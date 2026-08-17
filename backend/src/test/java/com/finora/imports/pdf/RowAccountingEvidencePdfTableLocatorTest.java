package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-accounting evidence: the three drop points in {@link PdfTableLocator#locateAll} wired to
 * record a {@link PdfTableLocator.DroppedCandidateRow} when the discarded line has transaction
 * shape (a date-shaped cell and a decimal-amount cell on the same row -- see
 * {@code isTransactionShapedRow}'s own doc comment). Every fixture below is fully hand-synthesized
 * -- invented account numbers, dates, and amounts -- per the Synthetic Fixture Policy; no value
 * from any real document appears here.
 *
 * <p>Deliberately narrow, matching {@link PdfTableLocator.LocatedSection}'s own doc comment: only
 * three of this class's many drop points are wired (the ones with zero trace at all before this
 * change, and the two the engineering-principles doc already documents as an acknowledged risk).
 * The rest are a documented, deliberate gap, not silently assumed complete.
 */
class RowAccountingEvidencePdfTableLocatorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText line(String text, float y) {
        return run(text, 40f, 180f, y);
    }

    private static List<PositionedText> ledgerHeader(float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run("Date", 70f, 30f, y));
        row.add(run("Description", 200f, 70f, y));
        row.add(run("Debit", 380f, 40f, y));
        row.add(run("Credit", 460f, 40f, y));
        row.add(run("Balance", 540f, 50f, y));
        return row;
    }

    private static List<PositionedText> ledgerRow(String date, String desc, String debit, String balance, float y) {
        List<PositionedText> r = new ArrayList<>();
        r.add(run(date, 71f, 42f, y));
        r.add(run(desc, 201f, desc.length() * 5.2f, y));
        r.add(run(debit, 381f, 40f, y));
        r.add(run(balance, 541f, 40f, y));
        return r;
    }

    @Test
    void aCleanStatement_recordsNoDroppedCandidates() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        positioned.addAll(ledgerRow("02.01.2026", "Grocery Store", "200.00", "9750.00", 150f));

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }

    /**
     * A footer line that matches {@code PAGE_FOOTER} ("page ... of ...") but ALSO happens to carry
     * a date-shaped cell and a decimal-amount cell -- the exact acknowledged risk the engineering
     * principles doc names ("a real transaction description... could in principle be misread...
     * bypassing even TransactionNormalizer/explainFailure()'s 'Never lose information' safety
     * net"). The line is still correctly excluded from the table (PAGE_FOOTER's own job, unchanged
     * by this evidence) -- this only asserts it no longer vanishes with zero trace.
     */
    @Test
    void aTransactionShapedPageFooterLine_isRecordedAsDroppedEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        // The footer's own cells: one carries "page ... of ...", a SEPARATE cell is a pure date, a
        // third is a pure decimal amount -- isTransactionShapedRow checks each cell independently.
        List<PositionedText> footer = new ArrayList<>();
        footer.add(run("Page 1 of 12", 40f, 100f, 150f));
        footer.add(run("02.01.2026", 200f, 60f, 150f));
        footer.add(run("45.00", 380f, 40f, 150f));
        positioned.addAll(footer);
        positioned.addAll(ledgerRow("03.01.2026", "Grocery Store", "200.00", "9750.00", 170f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        var dropped = doc.sections().get(0).evidence().droppedTransactionCandidates();
        assertThat(dropped).hasSize(1);
        assertThat(dropped.get(0).reason()).isEqualTo("PAGE_FOOTER_OR_CLOSING_MARKER");
        assertThat(dropped.get(0).signals()).contains("DATE_PRESENT", "AMOUNT_PRESENT");
    }

    /**
     * A repeated {@code SECTION_MARKER} banner for the SAME account -- ordinarily silently
     * discarded with no trace at all (the account is already open, so nothing about the repeat is
     * new information) -- built here to also carry a date-shaped and amount-shaped run on the same
     * line, the shape the engineering-principles doc names as an acknowledged risk for this
     * pattern specifically.
     */
    @Test
    void aTransactionShapedRepeatedBannerLine_isRecordedAsDroppedEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("SAVINGS ACCOUNT - 111111111111", 90f));
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        // The repeated banner: same account number, but with extra cells carrying a date and an
        // amount alongside it -- unlike the clean single-cell banner AccountIdentityLinePdfTableLocatorTest
        // uses, which deliberately carries neither.
        List<PositionedText> repeatedBanner = new ArrayList<>();
        repeatedBanner.add(run("SAVINGS ACCOUNT - 111111111111", 40f, 220f, 150f));
        repeatedBanner.add(run("02.01.2026", 280f, 60f, 150f));
        repeatedBanner.add(run("45.00", 380f, 40f, 150f));
        positioned.addAll(repeatedBanner);
        positioned.addAll(ledgerRow("03.01.2026", "Grocery Store", "200.00", "9750.00", 170f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        var dropped = doc.sections().get(0).evidence().droppedTransactionCandidates();
        assertThat(dropped).hasSize(1);
        assertThat(dropped.get(0).reason()).isEqualTo("REPEATED_ACCOUNT_BANNER");
    }

    /**
     * False-positive safety test (the same lesson as the payment-summary-panel phantom-section
     * fix): an ordinary page-footer line ("Page 1 of 12", with no date and no amount anywhere on
     * it) IS discarded by the existing {@code PAGE_FOOTER} pattern, exactly as before -- but must
     * NOT generate dropped-candidate evidence, since {@code isTransactionShapedRow} requires BOTH
     * a date-shaped cell and a decimal-amount cell, and this line has neither. Flagging every
     * ordinary footer line would make every paginated statement a REVIEW verdict, exactly the
     * false-confidence-eroding outcome this evidence exists to avoid -- this is the one test that
     * proves the shape GATE itself, not just that the three wired branches can fire.
     */
    @Test
    void anOrdinaryPageFooterWithNoDateOrAmount_neverGeneratesDroppedCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        positioned.add(line("Page 1 of 12", 150f));
        positioned.addAll(ledgerRow("02.01.2026", "Grocery Store", "200.00", "9750.00", 170f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }
}
