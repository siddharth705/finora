package com.finora.imports.pdf.fixtures;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds small, synthetic PDFs cell-by-cell (one {@code PDPageContentStream.showText()} call per
 * cell at an explicit x/y position) to exercise PdfPreviewGenerator/PdfTableLocator against
 * real-world PDF statement layout capabilities -- see
 * docs/engineering/financial-document-intelligence-principles.md for why these builder methods
 * and their matching tests are named after the capability each one exercises (a Dr/Cr amount
 * suffix, a running balance, a wrapped description, a composite multi-account statement, ...),
 * never a bank.
 *
 * The existing golden fixture (src/test/resources/pdf/separate_debit_credit_balance_sample.pdf,
 * originally named for the bank it was modeled on -- see that fixture's own test class for why it
 * was renamed) was built the same low-level way by an external script this repo doesn't carry (see
 * PdfPreviewGeneratorTest's own doc comment) -- that fixture's test already proves this technique
 * produces per-cell PositionedText runs granular enough for PdfTableLocator's nearest-x column
 * bucketing to work (its debit-vs-credit-by-position assertion would fail outright if PDFBox's
 * writeString() callback fired once per whole line instead of once per cell). This class exists so
 * new fixtures are regenerable from committed Java source instead of another un-reproducible
 * binary.
 *
 * <h2>Capability index</h2>
 * Methods below are grouped and ordered to match the Capability Registry in
 * docs/engineering/financial-document-intelligence-principles.md, not chronologically (when each
 * was added) -- so "which fixture exercises capability X" is a lookup here, not a grep across the
 * whole file. Per the refined test-corpus strategy (see that doc's own "Test Corpus Strategy"
 * section), this index only ever grows when a REAL document motivates a new fixture -- never
 * speculatively.
 *
 * <pre>
 * RUNNING_BALANCE / BALANCE_CHAIN_RECONSTRUCTION
 *   -&gt; buildReverseChronologicalRunningBalanceSample, buildReferenceNumberAndBalanceSample
 * DR_CR_SUFFIX (bare and parenthesized)
 *   -&gt; buildDrCrSuffixAmountColumnSample, buildParenthesizedDrCrRunningBalanceSample
 * LEADING_PLUS_CREDIT
 *   -&gt; buildWrappedDescriptionCreditCardSample
 * DATE_TIME_COLUMN
 *   -&gt; buildWrappedDescriptionCreditCardSample
 * WRAPPED_DESCRIPTION
 *   -&gt; buildWrappedDescriptionCreditCardSample
 * REPEATED_HEADER
 *   -&gt; buildDrCrSuffixAmountColumnSample, buildParenthesizedDrCrRunningBalanceSample
 * PAGE_BOUNDARY_ISOLATION / PAGE_FOOTER_EXCLUSION
 *   -&gt; buildParenthesizedDrCrRunningBalanceSample, buildStatementClosingMarkerSample
 * COMPOSITE_STATEMENT / MULTI_ACCOUNT
 *   -&gt; buildMultiSectionCompositeStatementSample
 * CREDIT_CARD_SUMMARY_SIGNAL
 *   -&gt; buildWrappedDescriptionCreditCardSample, buildMultiSectionCompositeStatementSample,
 *      buildGridMetadataFallbackSample
 * OFFSET_COLUMN_ANCHORS
 *   -&gt; buildOffsetColumnAnchorsSample, buildSingularDepositWithdrawalColumnsSample (via the
 *      leading-amount-in-balance split)
 * GRID_METADATA_FALLBACK (2-row grid)
 *   -&gt; buildGridMetadataFallbackSample, buildMultiColumnPaymentSummaryGridSample (multi-column
 *      variant + the leading-name-line account-holder pattern, both real-Axis-Bank-evidenced)
 * GRID_METADATA_TRAILING_LABEL
 *   -&gt; no PDF fixture here -- exercised directly against PdfMetadataExtractor.extract(List) with
 *      raw lines in PdfMetadataExtractorTest, since this capability's logic is pure string
 *      matching and doesn't need a rendered PDF to reach it.
 * LEADING_NARRATION_CONTINUATION
 *   -&gt; buildLeadingNarrationContinuationSample
 * MONTH_NAME_FIRST_DATES / DR_CR_DIRECTION_COLUMN / BLOCK_PITCH_CONTINUATION
 *   -&gt; buildMonthNameFirstDrCrColumnSample
 * NARRATION_ABOVE_ITS_DATE_ROW (leading narration decided by proximity, rehomed by header)
 *   -&gt; buildNarrationAboveItsDateRowSample
 * Never Lose Information (whole-document)
 *   -&gt; buildUnrecognizableDocumentSample
 * Composability (multiple already-evidenced capabilities firing together in one document)
 *   -&gt; buildRunningBalanceWrappedDescriptionRepeatedHeaderSample,
 *      buildOffsetAnchorsGridMetadataPageBoundarySample
 * Deferred capability evidence (real, single-document patterns preserved but NOT yet
 * implemented -- see the "Capability Backlog" table in the engineering principles doc)
 *   -&gt; no PDF fixture here either, same reasoning as GRID_METADATA_TRAILING_LABEL above --
 *      PdfMetadataExtractorTest#extract_doesNotYetRecognizeAnAccountHolderName_fromAValueThenLabelThenNameCompositeLine,
 *      PdfMetadataExtractorTest#extract_doesNotYetFindCreditLimit_inARealGridWhereAnUnrelatedRowSitsBetweenTheLabelAndItsValue
 * </pre>
 */
public final class PdfFixtureBuilder {

    private static final float FONT_SIZE = 9f;
    private static final float LEFT_MARGIN = 50f;
    // Kept comfortably under the row-to-row gap real single-line-spacing body text tends to use,
    // so a fixture's deliberate description-continuation row (see
    // buildWrappedDescriptionCreditCardSample) sits close enough to be recognized correctly by
    // PdfTableLocator's date-column-driven merge logic, while staying well clear of
    // ROW_Y_TOLERANCE (3pt) so ordinary adjacent rows are never merged into one visual row.
    private static final float ROW_HEIGHT = 10f;
    private static final float TOP_Y = 770f;

    private PdfFixtureBuilder() {}

    private record Cell(float x, String text) {}
    private record Row(float y, List<Cell> cells) {}

    /** One page's worth of rows, built top-down from {@link #TOP_Y}. */
    private static final class PageBuilder {
        final List<Row> rows = new ArrayList<>();
        float y = TOP_Y;

        /** A free-standing single-cell line at the left margin (bank letterhead, a marker line, a
         *  payment-summary sentence) -- not part of any table's column grid. */
        PageBuilder line(String text) {
            rows.add(new Row(y, List.of(new Cell(LEFT_MARGIN, text))));
            y -= ROW_HEIGHT;
            return this;
        }

        /** One table row, one cell per column anchor -- a null/blank value is simply omitted
         *  (matching how a real statement's ragged columns work; TransactionNormalizer already
         *  tolerates a row missing some columns entirely). */
        PageBuilder row(float[] colX, String... values) {
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < values.length && i < colX.length; i++) {
                if (values[i] != null && !values[i].isEmpty()) cells.add(new Cell(colX[i], values[i]));
            }
            rows.add(new Row(y, cells));
            y -= ROW_HEIGHT;
            return this;
        }

        PageBuilder blankLine() {
            y -= ROW_HEIGHT;
            return this;
        }
    }

    private static byte[] render(List<PageBuilder> pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (PageBuilder page : pages) {
                PDPage pdPage = new PDPage(PDRectangle.A4);
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), FONT_SIZE);
                    for (Row row : page.rows) {
                        for (Cell cell : row.cells()) {
                            cs.beginText();
                            cs.newLineAtOffset(cell.x(), row.y());
                            cs.showText(cell.text());
                            cs.endText();
                        }
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /**
     * Re-saves any fixture above as a password-protected document, so the SAME layout can be
     * parsed both with and without encryption and the two results compared directly.
     *
     * Deliberately a transform rather than a fixture, and deliberately absent from the capability
     * index: encryption is a property of the file container, not of the statement's layout, so it
     * composes with every fixture instead of being one more of them. Indian bank e-statements are
     * commonly delivered encrypted, which is why this exists at all.
     *
     * Sets only the USER password (the one needed to open the document); the owner password is
     * left blank, matching how banks issue these -- open-protected, not permission-protected.
     */
    public static byte[] encrypt(byte[] pdfBytes, String userPassword) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            AccessPermission permissions = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy("", userPassword, permissions);
            policy.setEncryptionKeyLength(128);
            doc.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    // ==================== RUNNING_BALANCE / BALANCE_CHAIN_RECONSTRUCTION ====================

    /**
     * A savings-account statement layout with an explicit Type (DR/CR) column and a running
     * balance, listed newest-first (reverse chronological) with a same-day 3-transaction cluster
     * to exercise BalanceChainUtil's chain reconstruction -- modeled on PNB ONE's export, but not
     * specific to it; any bank's statement generator could list transactions this same way.
     */
    public static byte[] buildReverseChronologicalRunningBalanceSample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 230f, 320f, 400f, 480f};

        PageBuilder page = new PageBuilder();
        page.line("Branch Name: JHANSI,SIPRI BAZAR")
                .line("IFSC: PUNB0222300")
                .blankLine()
                .row(col, "Date", "Instrument ID", "Amount(INR)", "Type (DR/CR)", "Balance", "Remarks")
                // File order is newest-first, exactly like the real export.
                .row(col, "26/07/2026", "", "377.71", "DR", "10075.86", "UPI/DR/Lonkar P")
                .row(col, "25/07/2026", "", "120.0", "DR", "10453.57", "UPI/DR/Mr Aman")
                .row(col, "25/07/2026", "", "145.0", "DR", "10573.57", "UPI/DR/Mohd Ali")
                .row(col, "25/07/2026", "", "9.7", "DR", "10718.57", "UPI/DR/Indian R")
                .row(col, "19/07/2026", "", "1600.0", "DR", "11147.27", "UPI/DR/Devarshi")
                .row(col, "18/07/2026", "", "1057.0", "CR", "12747.27", "UPI/CR/Aman Kum")
                .blankLine()
                .line("***Generated through PNB ONE***");

        return render(List.of(page));
    }

    /**
     * A savings-account statement layout with both a running balance AND a populated reference
     * number column on every row -- Phase 1 "capture facts"
     * (docs/engineering/financial-document-intelligence-principles.md), modeled on a real Canara
     * Bank statement's "Reference / Cheque No." column, but not specific to it. Distinct from
     * {@link #buildReverseChronologicalRunningBalanceSample} on purpose: that fixture's
     * Instrument ID column is always blank (matching the real PNB file it's modeled on), so it
     * can't exercise referenceNumber capture at all.
     *
     * Data hygiene note: this fixture's reference numbers, narration name fragments, and balances
     * were originally lifted verbatim from the real statement above -- a violation of the
     * Synthetic Fixture Policy (see the engineering principles doc), caught and fixed once this
     * cycle's Evidence Registry work surfaced it. The values below are fully synthetic; only the
     * structural properties the regression tests actually depend on (a 14-digit reference number
     * specifically, to preserve the column-width behavior described below; internally consistent
     * running-balance arithmetic) were preserved.
     */
    public static byte[] buildReferenceNumberAndBalanceSample() throws IOException {
        // Wider gap between the Reference and Amount columns than other fixtures use -- a
        // 14-digit reference value (e.g. "10203040506070") at FONT_SIZE runs wide enough to reach
        // a too-narrow next column's anchor and get merged into it by PdfTableLocator's
        // nearest-x bucketing, which every other fixture's shorter/blank Instrument ID values
        // never exercised.
        float[] col = {LEFT_MARGIN, 115f, 320f, 460f, 530f};

        PageBuilder page = new PageBuilder();
        page.line("CANARA BANK")
                .blankLine()
                .row(col, "Date", "Particulars", "Reference No", "Amount", "Balance")
                .row(col, "01/07/2026", "UPI/DR/234567890123/GENERIC MERCHANT", "234567890123", "-1000.00", "49000.00")
                .row(col, "01/07/2026", "MOB-IMPS/CR/RAHUL VERMA", "10203040506070", "1000.00", "50000.00")
                .row(col, "02/07/2026", "UPI/DR/345678901234/GENERIC PAYEE", "345678901234", "-150.00", "49850.00");

        return render(List.of(page));
    }

    // ==================== DR_CR_SUFFIX (bare and parenthesized) / REPEATED_HEADER ====================

    /**
     * A credit-card statement layout where a single Amount column carries a trailing Dr/Cr
     * suffix (no separate Type/Credit column), no running balance, and the header row repeats
     * verbatim on a second page -- modeled on Axis Bank's "Neo Rupay" statement, but not specific
     * to it; any bank/card issuer could ship this same column shape.
     *
     * Data hygiene note: the BBPS payment reference below was originally lifted from the real
     * statement above -- genericized per the Synthetic Fixture Policy (see the engineering
     * principles doc).
     */
    public static byte[] buildDrCrSuffixAmountColumnSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 350f, 470f};

        PageBuilder page1 = new PageBuilder();
        page1.line("AXIS BANK")
                .line("Neo Rupay Credit Card Statement")
                .line("Total Payment Due 27,665.16 Dr Minimum Payment Due 577.00 Dr")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB", "MISC STORE", "37.94 Dr")
                .row(col, "25/06/2026", "UPI/SAMPLE FOOD/PAYCO.S222222@PTY", "RESTAURANTS", "150.00 Dr")
                .row(col, "30/06/2026", "BBPS PAYMENT RECEIVED - DP000000000000AAAA", "", "10,081.99 Cr");

        PageBuilder page2 = new PageBuilder();
        // Same header repeated verbatim on the second page -- PdfTableLocator must recognize this
        // as "more of the same table," not a new section, and must not stage it as a data row.
        page2.row(col, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(col, "10/07/2026", "UPI/SAMPLEB/SAMPLEB.PAYU@HDFCBANK", "DEPT STORES", "249.00 Dr")
                .row(col, "13/07/2026", "UPI/MYNTRA DESIGNS PRIVATE L/MYNTRA1ONLINE.GPAY", "MISC STORE", "496.00 Dr");

        return render(List.of(page1, page2));
    }

    /**
     * A savings-account layout combining a parenthesized Dr/Cr amount suffix ("50000.00(Cr)",
     * "1627.00(Dr)" -- distinct from the bare trailing "37.94 Dr" form) with a running balance
     * column on the same statement, split across two pages with a page-number footer on the first
     * page and a repeated title banner at the top of the second -- both of which must be
     * recognized as noise, not folded into the last real transaction on page 1. Modeled on a real
     * Union Bank of India statement, but the pattern isn't specific to it.
     */
    public static byte[] buildParenthesizedDrCrRunningBalanceSample() throws IOException {
        // Amount/Balance headers are split into two separate cells ("Amount(" then a lone ")")
        // deliberately -- verified against the real statement this is modeled on, whose PDF
        // extracts those headers exactly this way (the currency glyph between the parens extracts
        // as nothing, leaving the parens as two distinct text runs). col[3]/col[5] are where a
        // real amount/balance VALUE actually lands (nearest the "Amount("/"Balance(" anchor, not
        // the lone ")" anchor next to it).
        // Transaction Id values are kept short (matching real ~9-character bank reference IDs)
        // deliberately -- a long value here would visually overlap the Remarks column that starts
        // right after it, and PDFBox interleaves two text runs that occupy overlapping x-ranges
        // on the same row character-by-character rather than keeping them as distinct cells. Real
        // column layouts don't overlap; this fixture shouldn't manufacture an overlap that
        // couldn't happen on an actual statement just to fit a longer test value.
        float[] col = {LEFT_MARGIN, 110f, 200f, 380f, 420f, 450f, 490f};

        PageBuilder page1 = new PageBuilder();
        page1.row(col, "Date", "Transaction Id", "Remarks", "Amount(", ")", "Balance(", ")")
                .row(col, "01-05-2026", "Y3922031", "Salary Credit", "50000.00(Cr)", "", "58234.84(Cr)", "")
                .row(col, "01-05-2026", "Y4898201", "UPI Payment", "34000.00(Dr)", "", "24234.84(Cr)", "")
                .line("Page 1 of 2");

        PageBuilder page2 = new PageBuilder();
        // A per-page title banner with no date of its own -- must NOT be folded into the last row
        // of page 1 above (the page-boundary guard this fixture exists to regression-test).
        page2.line("Savings Account")
                .row(col, "Date", "Transaction Id", "Remarks", "Amount(", ")", "Balance(", ")")
                .row(col, "02-05-2026", "S3533658", "Salary Credit", "15000.00(Cr)", "", "39234.84(Cr)", "");

        return render(List.of(page1, page2));
    }

    /**
     * A credit-card statement layout ending in a "**** End of Statement ****" closing marker
     * line, with no date of its own, directly after the last real transaction -- modeled on a
     * real Axis Bank Neo Rupay statement, where this line was being folded into the last real
     * transaction's description via the ordinary trailing-continuation merge (the same mechanism
     * a genuine wrapped description uses) instead of being recognized as boilerplate and
     * discarded, same underlying capability as {@link #buildParenthesizedDrCrRunningBalanceSample}'s
     * page-footer exclusion.
     */
    public static byte[] buildStatementClosingMarkerSample() throws IOException {
        // No merchant-category column here (unlike buildDrCrSuffixAmountColumnSample, which this
        // is otherwise modeled on) -- kept to just DATE/DESCRIPTION/AMOUNT with the amount column
        // pushed well clear of description, deliberately avoiding the known PDFBox-interleaving
        // pitfall a long description can trigger against a too-close next column (see
        // buildSingularDepositWithdrawalColumnsSample's own comment on this); irrelevant to what
        // this fixture exists to regression-test.
        float[] col = {LEFT_MARGIN, 110f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB", "37.94 Dr")
                .row(col, "15/07/2026", "UPI/SAMPLEB ENTERPRISES/PAYCO.S111111@PTY/73854", "1,240.00 Dr")
                .line("**** End of Statement ****");

        return render(List.of(page));
    }

    // ==================== LEADING_PLUS_CREDIT / DATE_TIME_COLUMN / WRAPPED_DESCRIPTION / CREDIT_CARD_SUMMARY_SIGNAL ====================

    /**
     * A credit-card statement layout with a combined date-and-time column, a leading "+" marking
     * a credit with no marker at all on a debit row, and a transaction's description that can
     * continue onto a second, dateless/amountless visual row which must fold into the row above
     * it -- modeled on HDFC's "Tata Neu Plus" statement, but not specific to it.
     *
     * Data hygiene note: the reference numbers and amounts below were originally lifted from the
     * real statement above -- genericized per the Synthetic Fixture Policy (see the engineering
     * principles doc) once this cycle's Evidence Registry work surfaced it.
     */
    public static byte[] buildWrappedDescriptionCreditCardSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 400f, 470f, 530f};

        PageBuilder page = new PageBuilder();
        page.line("HDFC BANK")
                .line("Tata Neu Plus HDFC Bank Credit Card Statement")
                .line("Total Amount Due 950.00 Minimum Due 100.00")
                .blankLine()
                .row(col, "DATE & TIME", "TRANSACTION DESCRIPTION", "Base NeuCoins", "AMOUNT", "PI")
                .row(col, "30/06/2026 14:18", "BPPY CC PAYMENT DP000000000000AAA", "", "+355.00", "l")
                // Continuation line: description-only, no date, no amount -- must fold into the
                // row above rather than becoming its own dropped, dateless row.
                .row(col, null, "(Ref# ST000000000000000000)", null, null, null)
                .row(col, "11/07/2026 19:34", "UPI-Amazon India", "", "942.50", "l");

        return render(List.of(page));
    }

    // ==================== COMPOSITE_STATEMENT / MULTI_ACCOUNT ====================

    /**
     * A multi-section "composite statement" layout: one PDF bundling a savings-account section
     * and a credit-card section, each introduced by its own account-type marker line and each
     * with its own, differently-shaped header/table -- the multi-section case
     * {@code PdfPreviewGenerator.generateSections} exists for. Modeled on HSBC India's "Composite
     * Statement," but not specific to it -- any bank could ship a multi-account PDF this shape.
     */
    public static byte[] buildMultiSectionCompositeStatementSample() throws IOException {
        float[] savingsCol = {LEFT_MARGIN, 130f, 300f, 380f, 460f};
        float[] ccCol = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("HSBC")
                .line("Composite Statement")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  120-070727-006")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4862 6989 2271 6048")
                .line("Total Amount Due 1,817.00 Minimum Due 200.00")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(ccCol, "15/07/2026", "UPI-Amazon India", "1,817.02 Dr");

        return render(List.of(page));
    }

    // ==================== FINANCIAL_PRODUCT_DISCOVERY ====================

    /**
     * A combined statement carrying three DIFFERENT products: a savings ledger, a fixed-deposit
     * schedule, and a recurring-deposit schedule -- preceded by a relationship summary that names
     * all three at the top of the document.
     *
     * This exists because the real trace that motivated the capability cannot prove it. That
     * capture predates {@code PdfTraceRedactor} having any deposit vocabulary in its allowlist, so
     * "Maturity Date" was redacted to "Xxxxxxxx Date" and "Deposit(Mnth)" to "Deposit(Xxxx)" before
     * it was committed -- the exact column headers product classification reads, removed from the
     * fixture meant to regression-test reading them. The allowlist is fixed, but a committed trace
     * cannot be un-redacted. A synthetic fixture contains no customer data at all, so it is allowed
     * to keep the vocabulary, and it is what the Test Corpus Strategy prescribes for exactly this
     * step (real document, root cause, generic capability, SYNTHETIC FIXTURE, regression test).
     *
     * The three shapes are modeled on the real combined statement's own structure:
     * <ul>
     *   <li>the relationship summary names "SAVINGS ACCOUNTS", "FIXED DEPOSITS" and "RECURRING
     *       DEPOSITS" together -- the leak that used to decide the deposit sections' identity, and
     *       which must now be recognised as document-level because it enumerates three products;</li>
     *   <li>the savings section is a ledger: dated rows with a narration and a running balance;</li>
     *   <li>the FD section is a schedule of figures with a maturity date, a rate and a principal,
     *       and deliberately carries a "Deposit(Mnth)" column -- the monthly contribution, which is
     *       the single word that used to make the whole section read as a transaction account;</li>
     *   <li>the RD section adds the installment fields that separate it from the FD.</li>
     * </ul>
     */
    public static byte[] buildCompositeMultiProductStatementSample() throws IOException {
        // Anchors are spaced wider than each header LABEL's rendered width at FONT_SIZE, not just
        // wider than its data. "Principal Amount" is ~72pt at 9pt Helvetica, so an anchor 60pt later
        // put the next header inside it and PdfTableLocator correctly merged them into one cell
        // ("Principal AmouSntart Date") -- a fixture defect that made this file misrepresent the
        // real layout it stands in for, since a real statement spaces columns to fit their headings.
        float[] summaryCol = {LEFT_MARGIN, 200f, 320f};
        float[] ledgerCol = {LEFT_MARGIN, 130f, 260f, 350f, 440f};
        float[] fdCol = {LEFT_MARGIN, 140f, 230f, 320f, 430f};
        float[] rdCol = {LEFT_MARGIN, 110f, 190f, 300f, 390f, 490f};

        PageBuilder page = new PageBuilder();
        page.line("Account Relationship Summary")
                .row(summaryCol, "Ccy", "Account Type", "Balance")
                .row(summaryCol, "INR", "SAVINGS ACCOUNTS", "83413.31")
                .row(summaryCol, "INR", "FIXED DEPOSITS", "124053.00")
                .row(summaryCol, "INR", "RECURRING DEPOSITS", "20000.00")
                .blankLine()
                .line("SAVINGS ACCOUNT  - 10000000000001")
                .line("Opening Balance 24818.22")
                .row(ledgerCol, "Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance")
                .row(ledgerCol, "05/06/2026", "Salary Credit", "", "55000.00", "79818.22")
                .row(ledgerCol, "10/06/2026", "Grocery Store", "2000.00", "", "77818.22")
                .row(ledgerCol, "18/06/2026", "Electricity Bill", "1404.91", "", "76413.31")
                .blankLine()
                .line("TERM DEPOSIT  - 20000000000002")
                .row(fdCol, "Principal Amount", "Start Date", "Deposit(Mnth)", "Maturity Date", "Rate of Interest")
                .row(fdCol, "100000.00", "12/03/2026", "0.00", "12/03/2027", "7.10")
                .row(fdCol, "24053.00", "01/05/2026", "0.00", "01/05/2027", "6.90")
                .blankLine()
                .line("RECURRING DEPOSIT  - 30000000000003")
                .row(rdCol, "Number", "Due Date", "Installment Paid", "Maturity Date", "Rate of Interest", "Status")
                .row(rdCol, "1", "05/05/2026", "5000.00", "05/05/2027", "6.75", "Paid")
                .row(rdCol, "2", "05/06/2026", "5000.00", "05/05/2027", "6.75", "Paid");

        return render(List.of(page));
    }

    // ==================== OFFSET_COLUMN_ANCHORS ====================

    /**
     * A layout where the header row's own column labels don't align with where each column's
     * DATA actually starts -- the header cells sit at one set of x-positions (as if centered over
     * a wide column) while the data rows' own values sit at a different, closer-together set.
     * Modeled on a real Axis Bank "Neo Rupay" statement (the exact coordinates that motivated this
     * -- header "TRANSACTION DETAILS" at x=183.5 vs. that column's own data starting at x=90.25,
     * much nearer the DATE column's anchor of 49.5 -- are documented on
     * {@code PdfTableLocator.bucketRow}'s own doc comment), but the underlying capability --
     * column data that doesn't line up with its own header label's x position -- isn't specific to
     * that statement; any bank's PDF generator could render headers and data with this kind of
     * offset. Also includes a fee line rendered as a single combined text run (its label and
     * trailing amount in one PDFBox {@code showText} call, not two, as some real fee/charge lines
     * are), and a wrapped fine-print paragraph broken into many small runs -- two of which happen
     * to be the bare words "amount" and "date" -- that must not be misread as a second table's
     * header and wrongly split this into two account sections.
     */
    public static byte[] buildOffsetColumnAnchorsSample() throws IOException {
        float[] headerCol = {LEFT_MARGIN, 183.5f, 386.5f, 514f};
        float[] dataCol = {35f, 90f, 372f, 500f};

        PageBuilder page = new PageBuilder();
        page.line("Neo Rupay Credit Card Statement")
                .blankLine()
                .row(headerCol, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(dataCol, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB", "MISC STORE", "37.94 Dr")
                .row(dataCol, "02/07/2026", "UPI/DR AGARWALS HEALTH CARE", "MEDICAL", "500.00 Dr")
                // Fee line: a separate date cell (as usual), then the description AND its trailing
                // amount as ONE combined cell -- no separate merchant-category or amount run at
                // all -- exactly the shape a per-run redirect can't catch, since there's only one
                // run to begin with; only the trailing-amount split recovers this row's amount.
                .row(new float[]{35f, 90f}, "04/07/2026", "FUEL SURCHARGE                                  10.00 Dr");

        // Fine print: a wrapped paragraph broken into 10 small runs, two of which are the bare
        // words "amount" and "date" -- ordinary English, not a real header.
        float[] proseCol = {35f, 90f, 145f, 200f, 255f, 310f, 365f, 420f, 475f, 530f};
        page.row(proseCol, "Interest", "on", "the", "outstanding", "amount", "is", "levied", "after", "the", "date");

        return render(List.of(page));
    }

    /**
     * A layout with singular "Withdrawal (Dr.)" / "Deposit (Cr.)" column headers (which normalize
     * to "withdrawal"/"deposit", not the plural "withdrawals"/"deposits" this pipeline previously
     * only recognized), a day-abbreviated-month-year date format ("01 Jul 2026"), and a reward/
     * cashback-style row that carries no value in either amount column at all -- just a small
     * amount and the resulting running balance combined into one Balance cell ("1.00 24352.97").
     * Modeled on a real Kotak Mahindra Bank statement, but none of these three shapes are specific
     * to that bank.
     */
    public static byte[] buildSingularDepositWithdrawalColumnsSample() throws IOException {
        // Description column kept short and Withdrawal/Deposit/Balance pushed well clear of it --
        // a longer description here would spatially overlap the amount columns' x-position and
        // PDFBox would interleave the two columns' text character-by-character (a fixture-design
        // pitfall hit before in this file; see PdfPreviewGeneratorTest's own history), not a real
        // statement's actual layout constraint.
        float[] col = {LEFT_MARGIN, 150f, 320f, 400f, 480f};

        PageBuilder page = new PageBuilder();
        page.line("Account Statement")
                .blankLine()
                .row(col, "Date", "Description", "Withdrawal (Dr.)", "Deposit (Cr.)", "Balance")
                .row(col, "01 Jul 2026", "IMPS to Landlord", "1000.00", null, "24361.97")
                .row(col, "01 Jul 2026", "UPI/SIVVA SURESH K", null, "10.00", "24351.97")
                // Cashback row: no separate Withdrawal or Deposit value at all -- just a leading
                // amount and the resulting balance combined in the Balance cell, exactly as PDFBox
                // extracts it on a real Kotak Mahindra Bank statement for this row shape.
                .row(col, "02 Jul 2026", "CASHBACK EARNED", null, null, "1.00 24352.97");

        return render(List.of(page));
    }

    // ==================== GRID_METADATA_FALLBACK (2-row grid) ====================

    /**
     * A credit-card statement whose payment-summary block lays the Due Date field out as a grid
     * (a label line ending in "...DUE DATE", an unrelated intervening line, then a value line
     * whose LAST date-shaped token is the actual due date) rather than a single "Label: Value"
     * line -- {@link com.finora.imports.pdf.PdfMetadataExtractor}'s bounded-window grid fallback
     * exists specifically for this shape. Modeled on a real HDFC "Tata Neu Plus" statement, but
     * the pattern isn't specific to it.
     */
    public static byte[] buildGridMetadataFallbackSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Credit Card Statement")
                .line("Total Payment Due 1,500.00 Minimum Amount Due 200.00")
                .blankLine()
                // The trailing label line the grid fallback looks for -- "AVAILABLE CREDIT LIMIT"
                // is irrelevant filler proving the fallback keys off the trailing "...DUE DATE"
                // phrase, not the whole line's content.
                .line("AVAILABLE CREDIT LIMIT MINIMUM DUE DUE DATE")
                // An unrelated intervening line (real statements commonly wrap a sub-label like
                // "(Including Cash)" onto its own line here) -- the fallback's bounded search
                // window must see past this to the value line below, not stop here.
                .line("(Including Cash)")
                // The value line: "200.00" corresponds to MINIMUM DUE, "09 Aug, 2026" to DUE
                // DATE -- values render in the same left-to-right order as their labels, so the
                // LAST date-shaped token on this line is the one that matters.
                .line("5000.00 200.00 09 Aug, 2026")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "15/07/2026", "Test Merchant Purchase", "500.00 Dr");

        return render(List.of(page));
    }

    /**
     * A credit-card statement whose payment-summary block is a genuine multi-column grid -- FIVE
     * labels on one header line ("Total Payment Due Minimum Payment Due Statement Period Payment
     * Due Date Statement Generation Date"), then all five values on the very next line, including
     * a "start - end" Statement Period range sharing the row with the standalone Payment Due Date
     * -- and a second such grid for Credit Limit (sharing the substring "Credit Limit" with
     * "Available Credit Limit" on the same header line). Also opens with the account holder's
     * plain name as the document's literal first line, no label at all. Modeled on a real Axis
     * Bank Neo Rupay statement (with a synthetic name/card number, never the real ones), the
     * combination of real gaps this fixture exists to regression-test:
     * {@code com.finora.imports.pdf.PdfMetadataExtractor} previously extracted NONE of Payment Due
     * Date, Credit Limit, or Account Holder Name from this exact real document.
     */
    public static byte[] buildMultiColumnPaymentSummaryGridSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("RAHUL VERMA")
                .line("Total Payment Due Minimum Payment Due Statement Period Payment Due Date Statement Generation Date")
                // Statement Period ("01/06/2026 - 30/06/2026") shares this row with the standalone
                // Payment Due Date ("20/07/2026") -- the fixture PdfMetadataExtractor's
                // range-exclusion logic exists to regression-test: without it, the period's own
                // start or end date would be picked instead of the real due date.
                .line("12,345.67 Dr 500.00 Dr 01/06/2026 - 30/06/2026 20/07/2026 30/06/2026")
                .blankLine()
                .line("Credit Card Number Credit Limit Available Credit Limit Available Cash Limit")
                .line("123456******7890 100,000.00 85,000.00 10,000.00")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "01/06/2026", "Test Merchant Purchase", "500.00 Dr");

        return render(List.of(page));
    }

    // ==================== LEADING_NARRATION_CONTINUATION ====================

    /**
     * A layout where each transaction's narration wraps across several lines BEFORE its own date
     * and amount row, not after -- the reverse of {@code buildWrappedDescriptionCreditCardSample}'s
     * shape. A transaction here reads, top to bottom: 2 narration lines with no date, then the
     * date+amount+balance row itself, then exactly 2 trailing detail lines (a transaction
     * time+reference line, then a "Chq: &lt;number&gt;" line) -- also with no date -- before the
     * NEXT transaction's own leading narration begins. Modeled on a real Canara Bank statement, but
     * the underlying capability -- narration that precedes its transaction's date row instead of
     * following it -- isn't specific to that bank.
     */
    public static byte[] buildLeadingNarrationContinuationSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 300f, 380f, 460f};
        float particularsX = col[1];

        PageBuilder page = new PageBuilder();
        page.row(col, "Date", "Particulars", "Deposits", "Withdrawals", "Balance")
                // Opening Balance: no date of its own, and must never absorb the narration that
                // follows it -- see MAX_TRAILING_CONTINUATION_ROWS's own doc comment for why a
                // summary row like this is closed to trailing continuation immediately.
                .row(col, null, null, "Opening Balance", null, "10000.00")
                // Transaction 1's leading narration -- two lines, no date on either.
                .row(new float[]{particularsX}, "UPI/CR/123456789012/JOHN DOE")
                .row(new float[]{particularsX}, "/BANK0001234/PAYMENT")
                .row(col, "15-07-2026", null, "500.00", null, "10500.00")
                // Transaction 1's trailing detail -- exactly 2 lines (the cap this capability is
                // sized to), no date on either.
                .row(new float[]{particularsX}, "14:30:00/REF123456")
                .row(new float[]{particularsX}, "Chq: REF123456")
                // Transaction 2's leading narration.
                .row(new float[]{particularsX}, "UPI/DR/987654321098/JANE SMITH")
                .row(new float[]{particularsX}, "/BANK0005678/PAYMENT")
                .row(col, "16-07-2026", null, null, "200.00", "10300.00")
                .row(new float[]{particularsX}, "09:15:00/REF789012")
                .row(new float[]{particularsX}, "Chq: REF789012");

        return render(List.of(page));
    }

    // ============ MONTH_NAME_FIRST_DATES / DR_CR_DIRECTION_COLUMN / BLOCK_PITCH_CONTINUATION ============

    /**
     * A savings-account layout with three properties that each broke a different stage of the
     * pipeline, modeled on a real Bandhan Bank statement (values fully synthetic, per the
     * Synthetic Fixture Policy) but none of them specific to that bank:
     *
     * <ol>
     *   <li>Dates written month-name-first with no separator before the day ("August22, 2026").
     *       Every date pattern the parser had put the day first, so nothing in the table parsed as
     *       a date, no row could become a transaction anchor, and the statement staged zero rows.</li>
     *   <li>A direction column headed with the marker itself, "Dr / Cr", rather than "Type".
     *       Unrecognised, every row -- credits included -- staged as an EXPENSE.</li>
     *   <li>Narration wrapping onto THREE trailing lines per transaction, one past
     *       {@code MAX_TRAILING_CONTINUATION_ROWS}. The third was buffered forward onto the next
     *       transaction, so every description carried a different transaction's tail.</li>
     * </ol>
     *
     * <p>The blank line between transactions is load-bearing, not cosmetic: it is what makes the
     * gap between blocks wider than the gap within one, which is the evidence
     * {@code PdfTableLocator.continuesTheBlock} requires before it will trust line pitch at all.
     * The real statement sets its narration at 10.8pt and separates transactions by 16.1pt; this
     * reproduces that relationship (10pt within, 20pt between) rather than those exact numbers.
     * Without it this fixture would be uniformly spaced, which is the shape
     * {@link #buildLeadingNarrationContinuationSample} covers -- and where the count cap, not the
     * pitch, is deliberately still the answer.
     *
     * <p>Listed newest-first with a running balance, as the real statement is. The chain reads
     * upward: 5,000.00 opening -> +2,500.00 -> +1,000.00 -> -750.50 -> 7,749.50.
     */
    public static byte[] buildMonthNameFirstDrCrColumnSample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 210f, 330f, 420f, 470f};
        float descriptionX = col[2];

        PageBuilder page = new PageBuilder();
        page.line("Current and Savings Account Statement")
                .blankLine()
                .row(col, "Transaction Date", "Value Date", "Description", "Amount", "Dr / Cr", "Balance")
                .blankLine()
                .row(col, "August22, 2026", "August22, 2026", "UPI/DR/D100000000001/", "INR750.50", "Dr", "INR7,749.50")
                .row(new float[]{descriptionX}, "Generic Merchant/abc/")
                .row(new float[]{descriptionX}, "merchant@abc/UPI/")
                .row(new float[]{descriptionX}, "ABC000000000000000000000000000001")
                .blankLine()
                .row(col, "August14, 2026", "August14, 2026", "UPI/CR/C200000000002/", "INR1,000.00", "Cr", "INR8,500.00")
                .row(new float[]{descriptionX}, "GENERIC SENDER TWO/")
                .row(new float[]{descriptionX}, "xyz/9000000000@xyzb/NA/")
                .row(new float[]{descriptionX}, "XYZ000000000000000000000000000002")
                .blankLine()
                .row(col, "August03, 2026", "August03, 2026", "UPI/CR/C300000000003/", "INR2,500.00", "Cr", "INR7,500.00")
                .row(new float[]{descriptionX}, "GENERIC SENDER THREE/")
                .row(new float[]{descriptionX}, "xyz/9000000000@xyzb/NA/")
                .row(new float[]{descriptionX}, "XYZ000000000000000000000000000003");

        return render(List.of(page));
    }

    // ============ NARRATION_ABOVE_ITS_DATE_ROW ============

    /**
     * A layout that prints each transaction as three separate visual lines -- narration head, then
     * the date/amount/balance row, then the narration's wrapped tail -- with a blank line between
     * transactions, and whose narration text sits nearer the DATE column's anchor than its own
     * NARRATION anchor. Modeled on a real Bank of Baroda statement (values fully synthetic per the
     * Synthetic Fixture Policy); the shape is not specific to that bank.
     *
     * <p>Every description on that statement came back BLANK, from two bugs that only bite together:
     *
     * <ol>
     *   <li>The narration buckets into the date column (its x is 30pt from the DATE anchor and 61pt
     *       from NARRATION), so the continuation merge correctly refuses to append it onto a valid
     *       date and redirects it to the description column -- but the redirect looked the column up
     *       in the ROW's keys, and on this layout nothing ever lands in NARRATION while the row is
     *       being built. It found nothing and dropped the text.</li>
     *   <li>The narration head belongs to the transaction BELOW it, not the one above. Counting
     *       dateless rows cannot see that, so each head was absorbed by the previous transaction --
     *       every description carrying the following payment's merchant.</li>
     * </ol>
     *
     * <p>The blank line between transactions is load-bearing: it is what makes each narration head
     * measurably closer to its own date row (10pt) than to the transaction above it (20pt), which is
     * the evidence {@code PdfTableLocator.belongsToTheRowAbove} needs. The real statement's numbers
     * are 5.11pt and 10.21pt; this reproduces the relationship, not the values.
     *
     * <p>The leading "Opening Balance" row is the real statement's too, and it matters: it is the
     * row that used to swallow the first transaction's narration. It is dated a day before the
     * first transaction only so the tests can address the two rows separately; the real statement
     * dates both the same, and nothing here depends on the dates differing.
     */
    public static byte[] buildNarrationAboveItsDateRowSample() throws IOException {
        float[] col = {LEFT_MARGIN, 141f, 252f, 308f, 423f, 530f};
        // Left of the NARRATION anchor and nearer to DATE -- the whole point of the fixture.
        float narrationX = 80f;

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "NARRATION", "CHQ.NO.", "WITHDRAWAL (DR)", "DEPOSIT (CR)", "BALANCE")
                .row(col, "01-06-2026", "Opening Balance", null, null, null, "38458.16 Cr")
                .blankLine()
                .row(new float[]{narrationX}, "UPI/100000000001/02:44:32/UPI/firstmerchant")
                .row(col, "02-06-2026", null, null, "1420.00", null, "37038.16 Cr")
                .row(new float[]{narrationX}, "one@bank")
                .blankLine()
                .row(new float[]{narrationX}, "UPI/200000000002/00:32:28/UPI/secondmerchant")
                .row(col, "03-06-2026", null, null, "1211.00", null, "35827.16 Cr")
                .row(new float[]{narrationX}, "two@bank")
                .blankLine()
                .row(new float[]{narrationX}, "UPI/300000000003/00:41:30/UPI/thirdmerchant")
                .row(col, "04-06-2026", null, null, "750.00", null, "35077.16 Cr")
                .row(new float[]{narrationX}, "three@bank");

        return render(List.of(page));
    }

    // ==================== Never Lose Information (whole-document) ====================

    /**
     * No header row, no table at all -- every line is a free-standing sentence
     * (`PdfTableLocator.locateAll` never recognizes a section). Exercises the "Never lose
     * information" whole-document gap: a real file this happens against (a statement in a layout
     * the engine genuinely doesn't understand yet) used to come back as a well-formed but
     * completely empty response, indistinguishable from a blank upload.
     */
    public static byte[] buildUnrecognizableDocumentSample() throws IOException {
        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("This statement uses a layout the engine does not recognize yet.")
                .line("Account summary: nothing here matches a known column header.");

        return render(List.of(page));
    }

    // ==================== Composability (multiple already-evidenced capabilities together) ====================
    // Refined test-corpus strategy (docs/engineering/financial-document-intelligence-principles.md):
    // every capability below is individually justified by its own real document elsewhere in this
    // file -- these two fixtures don't introduce anything new, they combine already-evidenced
    // capabilities in one document to prove they compose correctly, since a real statement rarely
    // activates only one capability at a time.

    /**
     * Combines three independently-evidenced capabilities in one document: a running balance
     * column (RUNNING_BALANCE), a transaction description that wraps onto a second, dateless row
     * (WRAPPED_DESCRIPTION), and a header row repeated verbatim on page 2 (REPEATED_HEADER).
     */
    public static byte[] buildRunningBalanceWrappedDescriptionRepeatedHeaderSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 400f, 470f};

        PageBuilder page1 = new PageBuilder();
        page1.row(col, "Date", "Description", "Amount", "Balance")
                .row(col, "01/07/2026", "Salary Credit", "50000.00", "50000.00")
                .row(col, "02/07/2026", "UPI-Amazon India Purchase", "1200.00", "48800.00")
                // Continuation line: description-only, no date/amount -- must fold into the row
                // above (WRAPPED_DESCRIPTION), not become its own dropped, dateless row.
                .row(col, null, "(Ref# ORDER-8817234451)", null, null);

        PageBuilder page2 = new PageBuilder();
        // Same header repeated verbatim on page 2 -- must be recognized as "more of the same
        // table" (REPEATED_HEADER), not staged as a garbage data row.
        page2.row(col, "Date", "Description", "Amount", "Balance")
                .row(col, "10/07/2026", "Grocery Store", "2000.00", "46800.00");

        return render(List.of(page1, page2));
    }

    /**
     * Combines three independently-evidenced capabilities in one document: header labels offset
     * from where their column's own data actually starts (OFFSET_COLUMN_ANCHORS), a Due Date
     * field laid out as a 2-row grid (GRID_METADATA_FALLBACK), and a page-number footer line that
     * must not be folded into the last real transaction row (PAGE_BOUNDARY_ISOLATION).
     */
    public static byte[] buildOffsetAnchorsGridMetadataPageBoundarySample() throws IOException {
        float[] headerCol = {LEFT_MARGIN, 183.5f, 386.5f, 514f};
        float[] dataCol = {35f, 90f, 372f, 500f};

        PageBuilder page1 = new PageBuilder();
        page1.line("Credit Card Statement")
                .line("AVAILABLE CREDIT LIMIT MINIMUM DUE DUE DATE")
                .line("5000.00 200.00 09 Aug, 2026")
                .blankLine()
                .row(headerCol, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(dataCol, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB", "MISC STORE", "37.94 Dr")
                .row(dataCol, "02/07/2026", "UPI/DR AGARWALS HEALTH CARE", "MEDICAL", "500.00 Dr")
                .line("Page 1 of 2");

        PageBuilder page2 = new PageBuilder();
        page2.row(headerCol, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(dataCol, "10/07/2026", "UPI/SAMPLEB/SAMPLEB.PAYU@HDFCBANK", "DEPT STORES", "249.00 Dr");

        return render(List.of(page1, page2));
    }
}
