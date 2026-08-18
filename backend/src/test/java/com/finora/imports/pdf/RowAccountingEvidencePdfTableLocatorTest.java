package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row-accounting evidence: the four drop points in {@link PdfTableLocator#locateAll} wired to
 * record a {@link PdfTableLocator.DroppedCandidateRow} when the discarded line has transaction
 * shape. Three use {@code isTransactionShapedRow} (a date-shaped cell and a decimal-amount cell on
 * the same row); the fourth (PRE_HEADER_ACTIVITY_CANDIDATE) deliberately uses a separate, more
 * permissive detector, {@code looksLikeFinancialActivityCandidate} -- see that method's own doc
 * comment for why it is not the same implementation. Every fixture below is fully hand-synthesized
 * -- invented account numbers, dates, and amounts -- per the Synthetic Fixture Policy; no value
 * from any real document appears here.
 *
 * <p>Deliberately narrow, matching {@link PdfTableLocator.LocatedSection}'s own doc comment: only
 * four of this class's many drop points are wired (the ones with zero trace at all before this
 * change, plus PRE_HEADER_ACTIVITY_CANDIDATE below). The rest are a documented, deliberate gap,
 * not silently assumed complete.
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

    /**
     * PRE_HEADER_ACTIVITY_CANDIDATE. Real shape, found against a real HSBC credit-card statement:
     * its one real transaction sits on a page whose own column header renders as part of a
     * background image (no extractable text at all), so the document's first RECOGNIZED header is
     * a later, unrelated table -- here, a differently-shaped ledger on the same page as the real
     * transaction row, standing in for that unrelated later table. Before this branch was wired,
     * this row vanished into {@code pendingAuxiliary} with zero trace.
     *
     * <p>Deliberately uses {@code isTransactionShapedRow}'s date format ("30JUN", no year) rather
     * than a full date: measured directly against the real statement, {@code isTransactionShapedRow}
     * returns false on this exact row (its {@code CsvParser.parseDate} check requires a year), which
     * is why this branch is backed by the separate, more permissive {@code
     * looksLikeFinancialActivityCandidate} instead -- see that method's own doc comment.
     */
    @Test
    void aWeaklyDatedActivityRowBeforeTheFirstAcceptedHeader_isRecordedAsDroppedEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        // No header has been accepted yet when this row is scanned -- it has to fail
        // looksLikeHeaderRow on its own (no date/header-name hints at all) to reach the branch
        // under test rather than being absorbed as a wrapped-header candidate.
        List<PositionedText> earlyTransaction = new ArrayList<>();
        earlyTransaction.add(run("30JUN", 71f, 42f, 90f));
        earlyTransaction.add(run("BBPS PMT reference12345", 120f, 90f, 90f));
        earlyTransaction.add(run("1,582.00", 380f, 60f, 90f));
        positioned.addAll(earlyTransaction);
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        var dropped = doc.sections().get(0).evidence().droppedTransactionCandidates();
        assertThat(dropped).hasSize(1);
        assertThat(dropped.get(0).reason()).isEqualTo("PRE_HEADER_ACTIVITY_CANDIDATE");
        assertThat(dropped.get(0).signals()).contains("DATE_PRESENT", "AMOUNT_PRESENT", "DESCRIPTION_PRESENT");
    }

    /**
     * False-positive safety, mirroring {@link #anOrdinaryPageFooterWithNoDateOrAmount_neverGeneratesDroppedCandidateEvidence}:
     * ordinary pre-header boilerplate (an account-holder name line, with no date and no amount
     * anywhere on it) must not generate evidence just because it precedes the first header.
     */
    @Test
    void ordinaryPreHeaderTextWithNoDateOrAmount_neverGeneratesDroppedCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("MR JOHN SMITH", 90f));
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }

    /**
     * The false-positive class this evidence is deliberately narrower than {@code
     * isTransactionShapedRow} to avoid: a loan/EMI-style row with a genuine date and a genuine
     * amount (a loan booking date and a principal, structurally identical to a transaction date
     * and amount) but no third, description-like cell -- exactly the shape a real HSBC credit-card
     * statement's own Loan Summary table row has. Two signals alone must not be enough here, unlike
     * {@code isTransactionShapedRow}'s own two-signal gate -- see {@code
     * looksLikeFinancialActivityCandidate}'s own doc comment for why a third signal is required
     * specifically at this drop point.
     */
    @Test
    void aDateAndAmountWithNoDescriptiveText_neverGeneratesActivityCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        List<PositionedText> loanShapedRow = new ArrayList<>();
        loanShapedRow.add(run("26 FEB 2026", 71f, 60f, 90f));
        loanShapedRow.add(run("11946.11", 380f, 60f, 90f));
        positioned.addAll(loanShapedRow);
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }

    /**
     * Named-product false-positive guard: a Recurring Deposit row that WOULD otherwise satisfy all
     * three signals (date, amount, and a description-like cell) is still refused, because
     * "Recurring Deposit" is one of {@code NON_TRANSACTION_PRODUCT_HINTS} -- an RD belongs to a
     * future Investments/Deposits domain, not the transaction ledger.
     */
    @Test
    void aRecurringDepositRow_neverGeneratesActivityCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        List<PositionedText> rdRow = new ArrayList<>();
        rdRow.add(run("15 MAR 2026", 71f, 60f, 90f));
        rdRow.add(run("Recurring Deposit", 120f, 90f, 90f));
        rdRow.add(run("50000.00", 380f, 60f, 90f));
        positioned.addAll(rdRow);
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }

    /**
     * Named-product false-positive guard: a Fixed Deposit row, same shape as the RD guard above.
     */
    @Test
    void aFixedDepositRow_neverGeneratesActivityCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        List<PositionedText> fdRow = new ArrayList<>();
        fdRow.add(run("10 JAN 2026", 71f, 60f, 90f));
        fdRow.add(run("Fixed Deposit", 120f, 90f, 90f));
        fdRow.add(run("100000.00", 380f, 60f, 90f));
        positioned.addAll(fdRow);
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }

    /**
     * Named-product false-positive guard: an EMI schedule row. "EMI" matches as a single word
     * within the multi-word description cell, unlike the multi-word RD/FD hints above which
     * require the whole cell to match -- see {@code matchesAnyHint}'s own two-tier behaviour.
     */
    @Test
    void anEmiScheduleRow_neverGeneratesActivityCandidateEvidence() {
        List<PositionedText> positioned = new ArrayList<>();
        List<PositionedText> emiRow = new ArrayList<>();
        emiRow.add(run("05 FEB 2026", 71f, 60f, 90f));
        emiRow.add(run("EMI Payment", 120f, 90f, 90f));
        emiRow.add(run("4082.00", 380f, 60f, 90f));
        positioned.addAll(emiRow);
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().droppedTransactionCandidates()).isEmpty();
    }
}
