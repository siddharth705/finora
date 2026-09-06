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
 * TRANSACTION_TABLE_CLOSED
 *   -&gt; buildStatementClosingMarkerWithTrailingLookalikeSample
 * TRANSACTION_TABLE_TOTAL_CLOSED
 *   -&gt; buildTransactionTableTotalMarkerWithTrailingLookalikeSample
 * MITC_SECTION_CLOSED
 *   -&gt; buildMitcSectionMarkerWithTrailingLookalikeSample,
 *      buildMixedCaseMitcMentionDoesNotCloseSample (negative case)
 * COMPOSITE_STATEMENT / MULTI_ACCOUNT
 *   -&gt; buildMultiSectionCompositeStatementSample
 * CREDIT_CARD_SUMMARY_SIGNAL
 *   -&gt; buildWrappedDescriptionCreditCardSample, buildMultiSectionCompositeStatementSample,
 *      buildGridMetadataFallbackSample
 * CREDIT_CARD_SUMMARY_SIGNAL (negative evidence -- documents that must NOT be classified as one)
 *   -&gt; buildIncidentalCardNumberSecurityNoticeSample, buildRepeatedCreditLimitColumnSummarySample,
 *      buildOneDistinctCreditCardPhraseSample / buildTwoDistinctCreditCardPhrasesSample (the
 *      distinct-phrase threshold, either side of it), buildCardNumberColumnOnlySample (the
 *      independent row-header path), buildOverdraftTermsCurrentAccountSample (adversarial probe)
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
 * WRAPPED_HEADER (one heading row printed across two visual lines, centered per column)
 *   -&gt; buildWrappedHeaderDepositScheduleSample
 * SUMMARY_ATTRIBUTION (a document-level printed summary, and how many sections could own it)
 *   -&gt; buildSummaryWithOneTransactionalSectionSample, buildSummaryWithTwoTransactionalSectionsSample
 * PRINTED_ACTIVITY_WITH_ZERO_STAGED (the statement claims activity; nothing reached the ledger)
 *   -&gt; buildPrintedSummaryNoReadableTableSample
 * SYNTHETIC GROUND TRUTH (a definition rendered to PDF, with expectations emitted independently)
 *   -&gt; render(SyntheticStatementDefinition)
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
        page.line("Branch Name: SAMPLETOWN,MAIN BAZAR")
                .line("IFSC: PUNB0999999")
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
        // 14-digit reference value (e.g. "10203040506070") at FONT_SIZE runs wide enough to reach   // synthetic-ok: 10-20-30-40-50-60-70, invented, not corpus-derived
        // a too-narrow next column's anchor and get merged into it by PdfTableLocator's
        // nearest-x bucketing, which every other fixture's shorter/blank Instrument ID values
        // never exercised.
        float[] col = {LEFT_MARGIN, 115f, 320f, 460f, 530f};

        PageBuilder page = new PageBuilder();
        page.line("CANARA BANK")
                .blankLine()
                .row(col, "Date", "Particulars", "Reference No", "Amount", "Balance")
                .row(col, "01/07/2026", "UPI/DR/234567890123/GENERIC MERCHANT", "234567890123", "-1000.00", "49000.00")
                .row(col, "01/07/2026", "MOB-IMPS/CR/SAMPLE SNDR", "10203040506070", "1000.00", "50000.00")   // synthetic-ok: 10-20-30-40-50-60-70, invented, not corpus-derived
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
                // Verbatim the wording a real Axis credit-card statement prints. Kept exactly as
                // captured: no "Credit Limit" clause has ever been committed on this line, and it
                // stays that way on purpose. The phrase list now carries "minimum payment due",
                // so this fixture proves the classifier works on Axis's ACTUAL vocabulary alone --
                // which is the only version of this fixture that proves anything.
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
                .row(col, "10/07/2026", "UPI/SAMPLEB/SAMPLEB.SPAY@SBANKONE", "DEPT STORES", "249.00 Dr")
                .row(col, "13/07/2026", "UPI/SAMPLE APPAREL PRIVATE L/SAMPLEAP.ONLINEPAY", "MISC STORE", "496.00 Dr");

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
                .row(col, "15/07/2026", "UPI/SAMPLEB ENTERPRISES/PAYCO.S111111@PTY/90000", "1,240.00 Dr")
                .line("**** End of Statement ****");

        return render(List.of(page));
    }

    /**
     * TRANSACTION_TABLE_CLOSED. Same fixture as {@link #buildStatementClosingMarkerSample}, plus a
     * row-shaped line AFTER the closing marker -- a date, a description, and an amount, exactly the
     * shape a real transaction row has. Real-corpus-evidenced: a genuine Minimum-Amount-Due
     * illustration table on a real Axis Bank Neo Rupay statement has this exact shape (date,
     * description, Dr/Cr, amount) after its own "**** End of Statement ****" line -- see
     * docs/architecture/system-design/transaction-boundary-phase2a-investigation.md. Before
     * PdfTableLocator.STATEMENT_CLOSING_MARKER closed the section at the marker itself (rather than
     * only excluding the marker LINE from continuation-merging), a row shaped like this one would
     * still have been bucketed as a candidate row, surviving only if some unrelated stage happened
     * to reject it -- exactly the "accidental, not structural" protection the investigation above
     * documents. This fixture proves the closure itself, not a downstream accident.
     */
    public static byte[] buildStatementClosingMarkerWithTrailingLookalikeSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT/SAMPLEA.PAYU@AXISB", "37.94 Dr")
                .row(col, "15/07/2026", "UPI/SAMPLEB ENTERPRISES/PAYCO.S111111@PTY/90000", "1,240.00 Dr")
                .line("**** End of Statement ****")
                .row(col, "25/09/2026", "Illustrative Purchase Example", "5,000.00 Dr");

        return render(List.of(page));
    }

    /**
     * TRANSACTION_TABLE_TOTAL_CLOSED. Phase 2C. A real Kotak Mahindra Bank credit-card statement
     * prints "Total Purchase & Other Charges  5,178.69" directly beneath its last real transaction,
     * before a MITC/fees-and-charges legal schedule begins -- the same failure shape as
     * TRANSACTION_TABLE_CLOSED (buildStatementClosingMarkerWithTrailingLookalikeSample), evidenced
     * from a different bank. See docs/architecture/system-design/transaction-boundary-phase2a-
     * investigation.md.
     */
    public static byte[] buildTransactionTableTotalMarkerWithTrailingLookalikeSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "12/03/2026", "SAMPLE RETAIL STORE", "450.00")
                .row(col, "18/03/2026", "UPI-SAMPLE0001234567-SAMPLEVENDOR", "225.50") // synthetic-ok
                .line("Total Purchase & Other Charges                                          675.50") // synthetic-ok
                .row(col, "05/05/2026", "Illustrative Fee Example", "1,000.00");

        return render(List.of(page));
    }

    /**
     * MITC_SECTION_CLOSED. Phase 2C. A real ICICI Bank credit-card statement opens its MITC/legal
     * appendix with an all-caps "MOST IMPORTANT TERMS AND CONDITIONS (MITC)" heading immediately
     * after the last real transaction and its rewards summary -- same failure shape again,
     * evidenced from a third bank. See the Phase 2A/2C investigation doc.
     */
    public static byte[] buildMitcSectionMarkerWithTrailingLookalikeSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "09/04/2026", "SAMPLE ONLINE SERVICE IN", "899.00")
                .row(col, "16/04/2026", "SAMPLE SUBSCRIPTION APP IN", "1,499.00")
                .line("MOST IMPORTANT TERMS AND CONDITIONS (MITC)")
                .row(col, "11/09/2026", "Illustrative Interest Example", "500.00");

        return render(List.of(page));
    }

    /**
     * MITC_SECTION_CLOSED, negative case. {@link PdfTableLocator#MITC_SECTION_MARKER} is
     * deliberately case-sensitive -- real AU and SBI statements both mention the same concept in
     * ordinary mixed-case prose ("Most Important Terms and conditions" / "Most Important Terms &
     * Conditions") WHILE their own real transactions are still ongoing, well before the document's
     * true end. A case-insensitive match would close those documents' sections early. This fixture
     * reproduces that mixed-case shape, with a real-looking transaction row after it, and asserts
     * the row survives -- proving the case sensitivity is load-bearing, not incidental.
     */
    public static byte[] buildMixedCaseMitcMentionDoesNotCloseSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "09/04/2026", "SAMPLE ONLINE SERVICE IN", "899.00")
                .line("Log onto examplebank.com to view the \"Most Important Terms & Conditions\"")
                .row(col, "16/04/2026", "SAMPLE SUBSCRIPTION APP IN", "1,499.00");

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
                // Verbatim the wording a real HDFC credit-card statement prints -- see the note on
                // buildDrCrSuffixAmountColumnSample: no "Credit Limit" clause has ever been
                // committed on this line either, for the same reason.
                .line("Total Amount Due 950.00 Minimum Due 100.00")
                .blankLine()
                .row(col, "DATE & TIME", "TRANSACTION DESCRIPTION", "Base NeuCoins", "AMOUNT", "PI")
                .row(col, "30/06/2026 14:18", "BPPY CC PAYMENT DP000000000000AAA", "", "+355.00", "l")
                // Continuation line: description-only, no date, no amount -- must fold into the
                // row above rather than becoming its own dropped, dateless row.
                .row(col, null, "(Ref# ST000000000000000000)", null, null, null)
                .row(col, "11/07/2026 19:34", "UPI-Retailer One", "", "942.50", "l");

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
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4000 1111 2222 3333")
                // Real HDFC-style card payment-summary wording, unmodified -- see the note on
                // buildDrCrSuffixAmountColumnSample.
                .line("Total Amount Due 1,817.00 Minimum Due 200.00")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(ccCol, "15/07/2026", "UPI-Retailer One", "1,817.02 Dr");

        return render(List.of(page));
    }

    /**
     * The same composite statement as {@link #buildMultiSectionCompositeStatementSample}, with one
     * addition: the savings section's own table states its transaction date range inline, the same
     * "Transaction details from X to Y" phrasing {@link TransactionTableDateRangeExtractor} reads
     * (see that class's own doc comment) -- printed once, document-wide, the way a real repeated
     * table-header row would appear if PDFBox only rendered it once per section.
     *
     * <p>Exists to prove {@code PdfPreviewGenerator}'s own scoping fix: this extractor is read
     * document-wide (like {@code CreditCardSummaryExtractor}), but unlike a credit-card billing
     * panel it is not restricted to credit-card documents, so "read once, apply everywhere" is only
     * safe when the document IS effectively one section. Here it genuinely is not -- a savings
     * section and an unrelated credit-card section -- so the printed range belongs to neither
     * section's `DetectedAccountInfo`, not to both.
     */
    public static byte[] buildMultiSectionCompositeStatementWithTableHeaderDateRangeSample()
            throws IOException {
        float[] savingsCol = {LEFT_MARGIN, 130f, 300f, 380f, 460f};
        float[] ccCol = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("HSBC")
                .line("Composite Statement")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .line("Transaction details from 05-Jul-2026 to 10-Jul-2026")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4000 1111 2222 3333")
                .line("Total Amount Due 1,817.00 Minimum Due 200.00")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(ccCol, "15/07/2026", "UPI-Retailer One", "1,817.02 Dr");

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

    /**
     * A single Recurring Deposit account whose own summary table (rate, principal-equivalent
     * installment amount, maturity date) is immediately followed -- no identity banner, no blank
     * line -- by its own separate installment schedule (a different column set entirely, no
     * maturity/interest columns of its own). Modeled on a real HDFC composite statement's own RD
     * block, which prints exactly this shape and forces {@code
     * PdfPreviewGenerator#mergeOrphanedInvestmentFragments} to exist: the schedule alone never
     * carries enough of RD's own expected signals to classify above threshold, so without the
     * merge it falls to UNKNOWN and its real installment rows never stage, even though {@link
     * PdfTableLocator} correctly extracts every one of them.
     */
    public static byte[] buildOrphanedInvestmentScheduleSample() throws IOException {
        // Anchors spaced wider than each header LABEL's own rendered width at FONT_SIZE (see this
        // method's sibling buildCompositeMultiProductStatementSample's own comment on why -- a
        // narrower gap here previously let "Installment Amount" and "Sequence Number" render into
        // their own neighbour, garbling both into one unreadable run).
        float[] summaryCol = {LEFT_MARGIN, 170f, 290f, 400f};
        float[] scheduleCol = {LEFT_MARGIN, 180f, 290f, 400f};

        PageBuilder page = new PageBuilder();
        page.line("RD ACCOUNT SUMMARY")
                .row(summaryCol, "Account No", "Installment Amount", "Maturity Date", "Rate Of Interest")
                .row(summaryCol, "555123456", "1000.00", "20/03/2030", "6.75")
                .row(scheduleCol, "Sequence Number", "Due Date", "Amount Paid", "Installment Frequency")
                .row(scheduleCol, "1", "01/04/2026", "1000.00", "Monthly")
                .row(scheduleCol, "2", "01/05/2026", "1000.00", "Monthly");

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
                .row(dataCol, "02/07/2026", "UPI/DR SAMPLE HEALTH CENTRE", "MEDICAL", "500.00 Dr")
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
                .row(col, "01 Jul 2026", "UPI/SAMPLE PAYEE A", null, "10.00", "24351.97")
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
        page.line("SAMPLE SNDR")
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

    // ==================== WRAPPED_HEADER ====================

    /**
     * A table whose column headings are too long for their columns and are therefore printed on
     * TWO visual lines, with each heading's two halves centered over the column rather than
     * left-aligned to it. Modeled on the fixed-deposit schedule inside a real HDFC combined
     * statement (values fully synthetic per the Synthetic Fixture Policy); the shape is a
     * consequence of narrow columns and long headings, not of that bank.
     *
     * <p>That schedule's nine deposits imported as nothing, while the import reported success.
     * Both halves of its heading row are visible to the engine and NEITHER is a header:
     *
     * <ul>
     *   <li>the upper half carries the column names but no date word at all, so it fails
     *       {@code looksLikeHeaderRow}'s {@code hasDate} condition outright;</li>
     *   <li>the lower half carries "Date", but only one other recognized name across seven cells,
     *       so it fails the density condition that exists to keep prose from being read as a
     *       header.</li>
     * </ul>
     *
     * <p>Both conditions are correct individually. The mistake was asking them of one visual line
     * at a time, when the header a reader sees spans two.
     *
     * <p>The half-line offsets are load-bearing, and are what centering produces: the LONGER of a
     * heading's two lines starts further LEFT, so "Principal" sits left of "Deposit" above it
     * while "Number" sits right of "FD". The real statement's offsets run from 0.23pt to 13.77pt;
     * this reproduces that relationship at 2pt, not those values. A merge rule that assumed the
     * two lines shared a left edge would find nothing here.
     */
    public static byte[] buildWrappedHeaderDepositScheduleSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 165f, 235f, 300f, 365f, 440f};
        // Centered over the column: the shorter upper line starts right of the anchor, the longer
        // lower line starts left of it.
        float[] upper = {LEFT_MARGIN + 2f, 112f, 167f, 237f, 302f, 367f, 442f};
        float[] lower = {LEFT_MARGIN - 2f, 108f, 163f, 233f, 298f, 363f, 438f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("FD DETAILS :- FOR CURRENT ACCOUNT HOLDER")
                .row(upper, "FD", "Currency", "Deposit", "Open/Value", "Rate Of", "Maturity", "Nomination")
                .row(lower, "Number", "Code", "Principal", "Date", "Interest", "Amount", "Registered")
                .row(col, "FD0000001", "INR", "50,000.00", "12/01/2026", "7.10", "53,551.00", "YES")
                .row(col, "FD0000002", "INR", "25,000.00", "05/03/2026", "6.85", "26,712.00", "YES")
                .row(col, "FD0000003", "INR", "10,000.00", "18/04/2026", "6.60", "10,644.00", "NO");

        return render(List.of(page));
    }

    // ==================== SUMMARY_ATTRIBUTION ====================

    /**
     * A printed summary grid, one section that carries transactions, and two that do not.
     *
     * <p>The shape of the real HDFC combined statement: a savings ledger alongside a fixed-deposit
     * schedule and a recurring-deposit schedule, with the document's own debit/credit totals and
     * counts printed once, at the top, describing the whole file. Only the ledger stages
     * transactions -- the deposit schedules are rows of figures about products, not payments.
     *
     * <p>The printed figures describe the ledger exactly: 2 debits totalling 3,404.91 and 1 credit
     * of 55,000.00. That is deliberate, and it is what makes this fixture able to tell attribution
     * from non-attribution: if the summary reaches the ledger the rule VERIFIES, and if it does not
     * the rule can only report that it had nothing to compare against.
     */
    public static byte[] buildSummaryWithOneTransactionalSectionSample() throws IOException {
        float[] totalsCol = {LEFT_MARGIN, 150f, 260f, 380f};
        float[] countsCol = {LEFT_MARGIN, 150f};
        float[] ledgerCol = {LEFT_MARGIN, 130f, 260f, 350f, 440f};
        float[] fdCol = {LEFT_MARGIN, 140f, 230f, 320f, 430f};
        float[] rdCol = {LEFT_MARGIN, 110f, 190f, 300f, 390f, 490f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Statement Summary")
                .row(totalsCol, "Opening Balance", "Debit Amount", "Credit Amount", "Closing Balance")
                .row(totalsCol, "24818.22", "3404.91", "55000.00", "76413.31")
                .row(countsCol, "Debit Count", "Credit Count")
                .row(countsCol, "2", "1")
                .blankLine()
                .line("SAVINGS ACCOUNT  - 10000000000001")
                .row(ledgerCol, "Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance")
                .row(ledgerCol, "05/06/2026", "Salary Credit", "", "55000.00", "79818.22")
                .row(ledgerCol, "10/06/2026", "Grocery Store", "2000.00", "", "77818.22")
                .row(ledgerCol, "18/06/2026", "Electricity Bill", "1404.91", "", "76413.31")
                .blankLine()
                .line("TERM DEPOSIT  - 20000000000002")
                .row(fdCol, "Principal Amount", "Start Date", "Deposit(Mnth)", "Maturity Date", "Rate of Interest")
                .row(fdCol, "100000.00", "12/03/2026", "0.00", "12/03/2027", "7.10")
                .blankLine()
                .line("RECURRING DEPOSIT  - 30000000000003")
                .row(rdCol, "Number", "Due Date", "Installment Paid", "Maturity Date", "Rate of Interest", "Status")
                .row(rdCol, "1", "05/05/2026", "5000.00", "05/05/2027", "6.75", "Paid");

        return render(List.of(page));
    }

    /**
     * The same printed summary, and TWO sections that carry transactions.
     *
     * <p>Modeled on the composite savings-plus-credit-card shape
     * ({@link #buildMultiSectionCompositeStatementSample}), with a document-level summary added.
     * The totals still describe the FIRST section exactly, which is the trap: a rule that picked
     * the largest, the first, or the most populated section would verify here and be wrong, because
     * a document-level summary on a genuine two-account statement describes neither section on its
     * own. There is no corpus document of this shape, which is why it is built rather than
     * captured.
     */
    public static byte[] buildSummaryWithTwoTransactionalSectionsSample() throws IOException {
        float[] totalsCol = {LEFT_MARGIN, 150f, 260f, 380f};
        float[] countsCol = {LEFT_MARGIN, 150f};
        float[] ledgerCol = {LEFT_MARGIN, 130f, 260f, 350f, 440f};
        float[] ccCol = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Statement Summary")
                .row(totalsCol, "Opening Balance", "Debit Amount", "Credit Amount", "Closing Balance")
                .row(totalsCol, "24818.22", "3404.91", "55000.00", "76413.31")
                .row(countsCol, "Debit Count", "Credit Count")
                .row(countsCol, "2", "1")
                .blankLine()
                .line("SAVINGS ACCOUNT  - 10000000000001")
                .row(ledgerCol, "Txn Date", "Narration", "Withdrawals", "Deposits", "Closing Balance")
                .row(ledgerCol, "05/06/2026", "Salary Credit", "", "55000.00", "79818.22")
                .row(ledgerCol, "10/06/2026", "Grocery Store", "2000.00", "", "77818.22")
                .row(ledgerCol, "18/06/2026", "Electricity Bill", "1404.91", "", "76413.31")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4000 1111 2222 3333")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(ccCol, "15/07/2026", "UPI-Retailer One", "1,817.02 Dr");

        return render(List.of(page));
    }

    /**
     * A statement that prints its own totals and whose transaction table cannot be read at all.
     *
     * <p>The shape of a real SBI statement: a summary grid the engine reads perfectly -- "Dr Count
     * 5 / Cr Count 1 / Total Debits / Total Credits" over its figures -- and below it a ledger with
     * no recognisable header, so no table is located and nothing is staged. That combination is the
     * point. The document asserts that money moved; the importer accepted none of it; and before
     * this fixture existed the result carried no verification report at all.
     *
     * <p>The labels are spelled the way the summary extractor already reads them. The real
     * statement abbreviates them ("Dr Count") and glues a bracket to one ("Total Credits("), which
     * the vocabulary does not yet match -- a separate extraction defect, deliberately not fixed
     * here and not baked into this fixture, so that this test fails for the reason it names.
     */
    public static byte[] buildPrintedSummaryNoReadableTableSample() throws IOException {
        float[] totalsCol = {LEFT_MARGIN, 150f, 280f, 400f};
        float[] countsCol = {LEFT_MARGIN, 150f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Account Statement")
                .row(totalsCol, "Opening Balance", "Total Debits", "Total Credits", "Closing Balance")
                .row(totalsCol, "6098.10", "5000.00", "40000.00", "41098.10")
                .row(countsCol, "Debit Count", "Credit Count")
                .row(countsCol, "5", "1")
                .blankLine()
                // No header this engine can recognise, so no table is located and nothing stages.
                .line("Particulars of entries appear below in the bank's own arrangement")
                .line("01-07-2026 / by transfer / 40000.00 / 46098.10")
                .line("03-07-2026 / to clearing / 1000.00 / 45098.10")
                .line("07-07-2026 / to clearing / 4000.00 / 41098.10");

        return render(List.of(page));
    }

    /**
     * Phase C-8.1 fixture -- native extraction succeeds, the rows it produces reconcile against
     * the bank's own printed summary totals (a genuine, independent internal check --
     * {@link com.finora.imports.SummaryTotalsValidator}, not merely an unreached one), and yet the
     * document never states a closing balance anywhere: the ledger carries no running-balance
     * column at all (only Date/Narration/Withdrawals/Deposits), so
     * {@code PdfPreviewGenerator.buildDetectedAccountInfo}'s balance-point derivation -- which is
     * the ONLY mechanism that ever populates {@code DetectedAccountInfo.closingBalance()} on the
     * PDF path, see that method's own doc comment -- has nothing to derive it from, and
     * {@link com.finora.imports.StatementTotalsValidator} in turn reports {@code NOT_APPLICABLE}
     * for want of a closing balance to check, not a failure.
     *
     * <p>This is deliberately NOT the same shape as {@link #buildPrintedSummaryNoReadableTableSample}
     * (which fails to locate a table at all) or a balance-chain failure fixture (which fails an
     * arithmetic check). Every applicable check here either VERIFIES or is legitimately
     * NOT_APPLICABLE; nothing here looks suspicious under any signal the existing validators
     * produce. That is the point -- see the C-8.1 investigation this fixture exists for: the exact
     * "succeeded but incomplete" corpus gap that
     * {@code docs/architecture/system-design/} (C-8 investigation notes) identified as absent from
     * the fixture corpus.
     */
    public static byte[] buildReconciledSummaryNoBalanceColumnSample() throws IOException {
        float[] totalsCol = {LEFT_MARGIN, 150f, 280f};
        float[] countsCol = {LEFT_MARGIN, 150f};
        float[] ledgerCol = {LEFT_MARGIN, 130f, 300f, 400f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Account Statement")
                .row(totalsCol, "Total Debits", "Total Credits")
                .row(totalsCol, "3404.91", "55000.00")
                .row(countsCol, "Debit Count", "Credit Count")
                .row(countsCol, "2", "1")
                .blankLine()
                .row(ledgerCol, "Date", "Narration", "Withdrawals", "Deposits")
                .row(ledgerCol, "05/06/2026", "Salary Credit", "", "55000.00")
                .row(ledgerCol, "10/06/2026", "Grocery Store", "2000.00", "")
                .row(ledgerCol, "18/06/2026", "Electricity Bill", "1404.91", "");

        return render(List.of(page));
    }

    // ==================== CREDIT_CARD_SUMMARY_SIGNAL (negative evidence) ====================

    /**
     * A savings-account statement carrying the generic anti-phishing notice every Indian bank
     * prints regardless of account type ("never share your card number, PIN, OTP ..."), and
     * nothing else card-related at all.
     *
     * <p>This is the false-positive shape the credit-card text scan used to misclassify: one
     * isolated "card number" hit, in a SECURITY INSTRUCTION rather than a labelled field, was
     * enough on its own to prefill the review form's account type as CREDIT_CARD for a plainly
     * ordinary savings account. Observed on real Bank of Baroda and SBI savings statements; the
     * notice's wording below is generic on purpose, since the point is that every bank prints
     * some version of it and none of them mean "this is a card statement".
     *
     * <p>Deliberately contains NO phrase from the free-text signal list and NO card-number
     * COLUMN, so anything that classifies this section as a credit card did so off the notice.
     */
    public static byte[] buildIncidentalCardNumberSecurityNoticeSample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Savings Account Statement")
                .line("Security notice: bank officials never ask for your card number, PIN, OTP or password.")
                .line("Please do not share these details with anyone, including over phone or email.")
                .blankLine()
                .row(col, "Date", "Narration", "Withdrawals", "Deposits", "Balance")
                .row(col, "05/07/2026", "Salary Credit", "", "55000.00", "105000.00")
                .row(col, "10/07/2026", "Grocery Store", "2000.00", "", "103000.00")
                .row(col, "18/07/2026", "Electricity Bill", "1404.91", "", "101595.09");

        return render(List.of(page));
    }

    /**
     * A savings-account statement opening with a multi-account relationship SUMMARY whose shared
     * column header line ("... Credit Limit ...") is reprinted once per account category --
     * Deposits and Investments, then Borrowings -- so the SAME single phrase occurs several
     * times over while no second distinct phrase ever appears.
     *
     * <p>Modeled on a real HSBC combined statement, but the shape is a summary-table convention
     * rather than that bank's alone. It is the reason the credit-card text scan counts DISTINCT
     * phrases rather than occurrences: a naive occurrence count would read this document's
     * repeated column header as three times the evidence, when it is a single piece of evidence
     * printed three times -- and evidence about the customer's OTHER products at that, not about
     * the savings ledger this section actually is.
     */
    public static byte[] buildRepeatedCreditLimitColumnSummarySample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Relationship Summary")
                .line("Deposits and Investments      Balance      Credit Limit")
                .line("Savings Account                101,595.09   Not Applicable")
                .line("Borrowings                    Balance      Credit Limit")
                .line("Personal Loan                  0.00         Not Applicable")
                .line("Total Relationship Value      Balance      Credit Limit")
                .blankLine()
                .row(col, "Date", "Narration", "Withdrawals", "Deposits", "Balance")
                .row(col, "05/07/2026", "Salary Credit", "", "55000.00", "105000.00")
                .row(col, "10/07/2026", "Grocery Store", "2000.00", "", "103000.00")
                .row(col, "18/07/2026", "Electricity Bill", "1404.91", "", "101595.09");

        return render(List.of(page));
    }

    /**
     * A credit-card payment-summary panel with its "Total Amount Due" label and value on the same
     * row but at two distinct X positions -- the shape {@code CreditCardSummaryExtractor}'s GRID
     * strategy reads (see that class's own {@code sameRowSummaryBlock} test fixture, which this
     * mirrors through actual PDF rendering rather than hand-built {@code PositionedText} runs).
     * {@code buildDrCrSuffixAmountColumnSample} and {@code buildMultiColumnPaymentSummaryGridSample}
     * both render their payment-summary line as a single merged text run via {@code .line(...)},
     * which is enough for the free-text classification signal scan but not for
     * {@code CreditCardSummaryExtractor}'s coordinate-based label/value matching -- it needs the
     * label and its value as two separate positioned runs, which only {@code .row(...)} produces.
     */
    public static byte[] buildCreditCardTotalDueGridSample() throws IOException {
        float[] summaryCol = {LEFT_MARGIN, 250f};
        float[] col = {LEFT_MARGIN, 110f, 350f};

        PageBuilder page = new PageBuilder();
        // hasReconcilableFields() requires all four of these present, not just totalAmountDue on
        // its own -- CreditCardSummaryExtractor discards a partial reading entirely rather than
        // carrying just the one field it happened to find (see its own doc comment). Numbers add
        // up (20,000.00 + 8,665.16 - 1,000.00 = 27,665.16) but this test never checks reconciliation
        // itself, only that the total survives into DetectedAccountInfo.
        page.line("SAMPLE BANK")
                .line("Credit Card Statement")
                .row(summaryCol, "Previous Balance", "20,000.00")
                .row(summaryCol, "Purchases", "8,665.16")
                .row(summaryCol, "Payments / Credits", "1,000.00")
                .row(summaryCol, "Total Amount Due", "27,665.16")
                .line("Minimum Amount Due 577.00")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "Sample Merchant Purchase", "150.00 Dr");

        return render(List.of(page));
    }

    /**
     * The lower half of the distinct-signal boundary: a ledger whose free text carries EXACTLY
     * ONE phrase from the credit-card signal list ("Total Payment Due", here a bill-payment
     * reminder printed on an ordinary account statement) and no others.
     *
     * <p>Byte-for-byte identical to {@link #buildTwoDistinctCreditCardPhrasesSample} apart from
     * the one added line there, so a test pairing the two isolates the threshold itself rather
     * than any other difference between two documents.
     */
    public static byte[] buildOneDistinctCreditCardPhraseSample() throws IOException {
        return oneOrTwoDistinctPhraseLedger(false);
    }

    /**
     * The upper half of the same boundary: the identical ledger, plus a second DISTINCT phrase
     * ("Minimum Amount Due"). Two distinct phrases is where the credit-card text signal starts
     * firing -- see {@code PdfPreviewGenerator}'s MIN_CREDIT_CARD_TEXT_SIGNALS.
     */
    public static byte[] buildTwoDistinctCreditCardPhrasesSample() throws IOException {
        return oneOrTwoDistinctPhraseLedger(true);
    }

    private static byte[] oneOrTwoDistinctPhraseLedger(boolean secondPhrase) throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Account Statement")
                .line("Total Payment Due 1,500.00");
        if (secondPhrase) page.line("Minimum Amount Due 200.00");
        page.blankLine()
                .row(col, "Date", "Narration", "Withdrawals", "Deposits", "Balance")
                .row(col, "05/07/2026", "Salary Credit", "", "55000.00", "105000.00")
                .row(col, "10/07/2026", "Grocery Store", "2000.00", "", "103000.00");

        return render(List.of(page));
    }

    /**
     * A card statement whose only credit-card evidence is a labelled "Card Number" table COLUMN,
     * with no payment-summary free text anywhere.
     *
     * <p>The row-level header check ({@code CsvParser.hasHeaderMatch}) and the free-text scan are
     * two independent paths into the same signal; this fixture is the one that reaches the
     * former with the latter silent, which is what makes it able to fail if the two are ever
     * accidentally coupled -- narrowing the free-text list must not narrow the column check.
     */
    public static byte[] buildCardNumberColumnOnlySample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Card Account Statement")
                .blankLine()
                .row(col, "Date", "Card Number", "Transaction Details", "Amount (Rs.)")
                .row(col, "15/07/2026", "XXXX XXXX XXXX 3333", "UPI-Retailer One", "1,817.02 Dr")
                .row(col, "16/07/2026", "XXXX XXXX XXXX 3333", "Test Merchant Purchase", "500.00 Dr");

        return render(List.of(page));
    }

    /**
     * Adversarial probe, not a proven real document: an ordinary current account with an
     * overdraft facility, whose terms block mentions the overdraft's own "credit limit" and the
     * "minimum due" on it -- two hits in a document that is not a credit card.
     *
     * <p>This fixture used to clear the two-distinct-signal threshold and was pinned as a known
     * unfixed false positive. It no longer does, and the wording below is unchanged from when it
     * did: the credit-limit half is written the way an overdraft's terms actually read it, as
     * PROSE ("your sanctioned credit limit is 2,00,000.00") rather than as a labelled field, so
     * requiring the field shape drops it to a single signal. See the test that uses it.
     */
    public static byte[] buildOverdraftTermsCurrentAccountSample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Current Account Statement")
                .line("Overdraft facility: your sanctioned credit limit is 2,00,000.00.")
                .line("Interest on the overdraft is charged monthly; the minimum due is debited automatically.")
                .blankLine()
                .row(col, "Date", "Narration", "Withdrawals", "Deposits", "Balance")
                .row(col, "05/07/2026", "Customer Receipt", "", "55000.00", "105000.00")
                .row(col, "10/07/2026", "Vendor Payment", "2000.00", "", "103000.00");

        return render(List.of(page));
    }

    /**
     * A genuine credit-card statement whose payment summary MIXES two issuers' spellings --
     * "Total Amount Due" (HDFC's) and "Minimum Payment Due" (Axis's) -- neither of which was on
     * the free-text signal list when this fixture was written. It exists because that combination
     * was a real false negative: a card statement worded this way reached at most one listed
     * phrase and was classified SAVINGS.
     *
     * <p>Kept now as the regression guard on the widened phrase list. It is the strictest of the
     * card fixtures, because it carries no credit-limit field at all and no phrase that the older
     * list would have matched twice -- so it can only pass on the two spellings that were added.
     */
    public static byte[] buildRealWorldPaymentSummaryLabelWordingSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Some Card Issuer")
                .line("Credit Card Statement")
                .line("Total Amount Due 27,665.16 Dr Minimum Payment Due 577.00 Dr")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "UPI/SAMPLE VENDOR PRIVATE LT", "37.94 Dr")
                .row(col, "30/06/2026", "BBPS PAYMENT RECEIVED", "10,081.99 Cr");

        return render(List.of(page));
    }

    // ==================== SECTION INDEX SPACE ====================

    /**
     * Deposit schedules printed ABOVE the transactional sections -- the shape that makes filtered
     * and unfiltered section indices disagree.
     *
     * <p>Every other composite fixture in this file puts the transactional section first
     * ({@link #buildSummaryWithOneTransactionalSectionSample},
     * {@link #buildCompositeMultiProductStatementSample}), where a section index means the same
     * thing before and after {@code StagedAccountSectionFilter} drops the non-account sections.
     * That is exactly why they cannot catch an index-space defect, and why this fixture exists.
     *
     * <p>Four sections are located: a term-deposit schedule, a recurring-deposit schedule, a
     * savings ledger, and a credit-card section. The two deposit schedules stage NO transactions --
     * they are rows of figures about products, not payments -- so filtering drops them and the
     * savings ledger, at raw index 2, becomes staged section 0. A reader that indexes the raw list
     * with a staged index therefore lands on an empty deposit schedule while believing it is
     * reading savings.
     *
     * <p>The savings figures are chosen so the two coordinate spaces produce visibly opposite
     * answers rather than merely different ones: the ledger's balance chain is internally
     * consistent and ends at 103000.00, so a closing-balance claim of 103000.00 is genuinely
     * supported by the savings section and cannot be supported by an empty schedule that states no
     * balances at all.
     *
     * <p>This is not an invented shape. A real HDFC combined statement prints its FD and RD
     * schedules wherever the bank's template puts them, and above the savings ledger is an ordinary
     * placement -- see {@code StagedAccountSectionFilter}'s own note on why that ordering is the
     * common case rather than the exotic one.
     */
    public static byte[] buildDepositSchedulesBeforeCompositeAccountsSample() throws IOException {
        float[] fdCol = {LEFT_MARGIN, 140f, 230f, 320f, 430f};
        float[] rdCol = {LEFT_MARGIN, 110f, 190f, 300f, 390f, 490f};
        float[] savingsCol = {LEFT_MARGIN, 130f, 300f, 380f, 460f};
        float[] ccCol = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Combined Statement")
                .blankLine()
                .line("TERM DEPOSIT  - 20000000000002")
                .row(fdCol, "Principal Amount", "Start Date", "Deposit(Mnth)", "Maturity Date", "Rate of Interest")
                .row(fdCol, "100000.00", "12/03/2026", "0.00", "12/03/2027", "7.10")
                .blankLine()
                .line("RECURRING DEPOSIT  - 30000000000003")
                .row(rdCol, "Number", "Due Date", "Installment Paid", "Maturity Date", "Rate of Interest", "Status")
                .row(rdCol, "1", "05/05/2026", "5000.00", "05/05/2027", "6.75", "Paid")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4000 1111 2222 3333")
                .line("Total Amount Due 1,817.00 Minimum Due 200.00 Credit Limit 50,000.00")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                .row(ccCol, "15/07/2026", "UPI-Retailer One", "1,817.02 Dr");

        return render(List.of(page));
    }

    /**
     * The same leading-deposit-schedule shape, but with only ONE transactional section -- so
     * filtering collapses a multi-section document down to a single account.
     *
     * <p>This is the more common real-world shape, and the harder one to reason about: because the
     * filtered list holds exactly one account, {@code ImportService} routes it down the
     * SINGLE-account staging branch, and the confirm that follows carries no section index at all.
     * "No section index" then has to mean staged section 0 -- which is raw section 2 here, not raw
     * section 0. A reader that treats the absent index as raw index 0 assesses the term-deposit
     * schedule.
     *
     * <p>The savings ledger is identical to {@link #buildDepositSchedulesBeforeCompositeAccountsSample}'s
     * so the two cases can be asserted against the same expected figures, and any difference
     * between them is attributable to the staging branch rather than to the data.
     */
    public static byte[] buildDepositSchedulesBeforeSingleAccountSample() throws IOException {
        float[] fdCol = {LEFT_MARGIN, 140f, 230f, 320f, 430f};
        float[] rdCol = {LEFT_MARGIN, 110f, 190f, 300f, 390f, 490f};
        float[] savingsCol = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Combined Statement")
                .blankLine()
                .line("TERM DEPOSIT  - 20000000000002")
                .row(fdCol, "Principal Amount", "Start Date", "Deposit(Mnth)", "Maturity Date", "Rate of Interest")
                .row(fdCol, "100000.00", "12/03/2026", "0.00", "12/03/2027", "7.10")
                .blankLine()
                .line("RECURRING DEPOSIT  - 30000000000003")
                .row(rdCol, "Number", "Due Date", "Installment Paid", "Maturity Date", "Rate of Interest", "Status")
                .row(rdCol, "1", "05/05/2026", "5000.00", "05/05/2027", "6.75", "Paid")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00");

        return render(List.of(page));
    }

    /**
     * A deposit schedule BETWEEN two transactional sections -- the interspersed case, as opposed to
     * the leading case the two fixtures above cover.
     *
     * <p>Staged section 0 (savings) is raw section 0, so it is right in BOTH coordinate spaces and
     * proves nothing on its own. Staged section 1 (credit card) is raw section 2. A fix that only
     * accounted for non-account sections at the FRONT of the document -- by offsetting indices by a
     * leading count, say, instead of applying the real filter -- would be correct for section 0 and
     * wrong for section 1, and nothing else in the corpus would catch it.
     *
     * <p><b>Both</b> transactional sections carry their own running balance, and DIFFERENT ones
     * (103,000.00 and 20,500.00). That is what makes staged index 1 diagnostic: a reader that lands
     * on the deposit schedule instead reports NOT_APPLICABLE, because a schedule states no balances
     * to reconcile, while the current account reconciles and VERIFIES. Had the second section been a
     * credit-card table -- which states no balances either -- the right answer and the wrong answer
     * would have been the same word, and the test would have proved nothing.
     */
    public static byte[] buildDepositScheduleBetweenAccountsSample() throws IOException {
        float[] savingsCol = {LEFT_MARGIN, 130f, 300f, 380f, 460f};
        float[] fdCol = {LEFT_MARGIN, 140f, 230f, 320f, 430f};

        PageBuilder page = new PageBuilder();
        page.line("Some Financial Institution")
                .line("Combined Statement")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "05/07/2026", "Salary Credit", "55000.00", "", "105000.00")
                .row(savingsCol, "10/07/2026", "Grocery Store", "", "2000.00", "103000.00")
                .blankLine()
                .line("TERM DEPOSIT  - 20000000000002")
                .row(fdCol, "Principal Amount", "Start Date", "Deposit(Mnth)", "Maturity Date", "Rate of Interest")
                .row(fdCol, "100000.00", "12/03/2026", "0.00", "12/03/2027", "7.10")
                .blankLine()
                .line("CURRENT ACCOUNT-RES  200-222222-003")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "02/08/2026", "Vendor Payment In", "12000.00", "", "22000.00")
                .row(savingsCol, "09/08/2026", "Office Supplies", "", "1500.00", "20500.00");

        return render(List.of(page));
    }

    /**
     * Phase C-8.2 negative-case fixture -- a running-balance statement that is correct everywhere
     * except the LAST row's printed balance, which is off by 5.00. Modeled on the same layout as
     * {@link #buildReverseChronologicalRunningBalanceSample} (Date/Instrument ID/Amount/Type
     * (DR/CR)/Balance/Remarks), but forward-chronological and with no same-day rows, so the
     * derived opening-balance anchor ({@code PdfPreviewGenerator.buildDetectedAccountInfo}) lines
     * up with hand-computed arithmetic exactly, with no marker-row or same-day-ordering surprises.
     *
     * <p>The error is placed on the LAST row specifically so it cannot cascade into a second
     * discrepancy: every prior row's printed balance is exactly what the chain expects, so the
     * mismatch is confined to one row out of four checked pairs -- a single, scattered
     * discrepancy, not a systematic misread. This is the shape
     * {@link com.finora.imports.BalanceChainValidator}'s own anti-noise guards
     * ({@code MIN_DISCREPANCIES_FOR_FAILED}, {@code FAILED_THRESHOLD}) exist to tell apart from a
     * whole-column misread, and it is reported {@code WARNING}, not {@code FAILED}.
     */
    public static byte[] buildSingleTrailingBalanceDiscrepancySample() throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 230f, 320f, 400f, 480f};

        PageBuilder page = new PageBuilder();
        page.row(col, "Date", "Instrument ID", "Amount(INR)", "Type (DR/CR)", "Balance", "Remarks")
                .row(col, "01/07/2026", "", "500.00", "DR", "9500.00", "UPI/DR/Sample One")
                .row(col, "05/07/2026", "", "300.00", "DR", "9200.00", "UPI/DR/Sample Two")
                .row(col, "10/07/2026", "", "700.00", "DR", "8500.00", "UPI/DR/Sample Three")
                // Correct printed balance would be 8300.00 (8500.00 - 200.00) -- off by 5.00,
                // deliberately, and only here.
                .row(col, "15/07/2026", "", "200.00", "DR", "8305.00", "UPI/DR/Sample Four");

        return render(List.of(page));
    }

    // ==================== SYNTHETIC GROUND TRUTH ====================

    /**
     * Renders a {@link SyntheticStatementDefinition}. The definition is the source; this consumes it.
     *
     * <p>The direction matters and is the whole point of the arrangement. This method may not be
     * asked what it drew, and the ground-truth document may not be built from its output: an
     * expected value derived from the generator would be wrong in exactly the same way as a
     * generator defect, and the comparison would pass while proving nothing. Both artefacts descend
     * from the definition; neither descends from the other.
     *
     * <p>Deliberately plain. A ledger heading and dated rows, nothing that exercises a capability --
     * this fixture exists to test the ground-truth machinery, and a layout that also stressed the
     * parser would make a failure ambiguous between the two.
     */
    public static byte[] render(SyntheticStatementDefinition definition) throws IOException {
        float[] col = {LEFT_MARGIN, 130f, 300f, 380f, 460f};

        PageBuilder page = new PageBuilder();
        page.line("Synthetic Financial Institution");
        page.line("Document " + definition.documentId());

        for (SyntheticStatementDefinition.ExpectedEntity entity : definition.entities()) {
            if (entity.presence() == SyntheticStatementDefinition.Presence.ABSENT) continue;
            page.blankLine();
            page.line(bannerFor(entity));
            page.row(col, "Date", "Description", "Withdrawals", "Deposits", "Balance");
            for (SyntheticStatementDefinition.Row row : entity.rows()) {
                String amount = row.amount().toPlainString();
                page.row(col, row.date().format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")),
                        row.description(),
                        row.credit() ? "" : amount,
                        row.credit() ? amount : "",
                        "");
            }
        }
        return render(List.of(page));
    }

    /** A section banner the locator already recognises, so the definition's entities become the
     *  document's sections without this fixture needing a capability of its own. */
    private static String bannerFor(SyntheticStatementDefinition.ExpectedEntity entity) {
        String number = entity.accountNumberMasked() == null ? "90000000000001"
                : entity.accountNumberMasked().replaceAll("[^0-9]", "") + "0000000001";
        return switch (entity.product()) {
            case "FIXED_DEPOSIT", "RECURRING_DEPOSIT" -> "DEPOSIT - " + number;
            default -> "SAVINGS ACCOUNT  - " + number;
        };
    }

    /**
     * The same definition, as a genuinely scanned document: rendered to images with no text layer.
     *
     * <p>Deliberately the SAME definition that produces the native fixture and the ground-truth
     * document. That is what makes a later comparison meaningful -- native acquisition and
     * recognition can both be judged against one declaration, rather than each against whatever the
     * other produced. See {@link ScannedPdfFixture} for why the result is byte-reproducible.
     */
    public static byte[] renderScanned(SyntheticStatementDefinition definition) throws IOException {
        return ScannedPdfFixture.scan(render(definition));
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

    /**
     * A savings ledger whose only rows are its own opening/closing balance markers plus a printed
     * "Transaction Count" line reading zero on both the deposit and withdrawal side -- the shape
     * {@link com.finora.imports.ExplicitZeroActivityDetector} exists to recognise. Modeled on a
     * real HSBC composite statement in the corpus; every value here is invented.
     *
     * <p>Every row carries its own date, unlike the real document (where only the first row does
     * and the rest rely on row-continuation to inherit it) -- deliberately, so this fixture proves
     * the detector and its wiring without depending on {@code PdfTableLocator}'s continuation
     * heuristics, which are a separate, already independently-tested concern.
     *
     * <p>The Turnover and Count rows carry the SAME Balance value as the two rows above them, and
     * that is not incidental. {@code TransactionNormalizer}'s {@code RowKind} rule is: an
     * explicitly-zeroed transactional column (Deposits=Withdrawals=0) still classifies as an
     * ordinary {@code TRANSACTION} unless the row ALSO carries a Balance-style column -- a Balance
     * value is what tips it to {@code BALANCE_MARKER} instead, keeping it out of the staged rows
     * this fixture must produce zero of. The real evidencing document prints its Turnover row this
     * way; verified by first building this fixture WITHOUT a Balance value on these two rows and
     * watching them wrongly stage as two zero-amount transactions instead of triggering the
     * detector.
     */
    public static byte[] buildExplicitZeroTransactionCountSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 320f, 400f, 480f};

        PageBuilder page = new PageBuilder();
        page.line("Composite Statement")
                .blankLine()
                .row(col, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(col, "01 Jul 2026", "BALANCE BROUGHT FORWARD", null, null, "500.00")
                .row(col, "01 Jul 2026", "CLOSING BALANCE", null, null, "500.00")
                .row(col, "01 Jul 2026", "Transaction Turnover", "0.00", "0.00", "500.00")
                .row(col, "01 Jul 2026", "Transaction Count", "0", "0", "500.00");

        return render(List.of(page));
    }

    /**
     * The same explicit-zero savings ledger as {@link #buildExplicitZeroTransactionCountSample},
     * bundled into a two-section composite statement alongside a SECOND, unrelated section whose
     * one row fails to stage for a genuine, unrelated reason -- its amount column resolves but
     * does not parse as a number, the shape {@code TransactionNormalizer.hasUnparseableRecognizedAmount}
     * exists for.
     *
     * <p>Built specifically to prove the cross-section guard in {@code PdfPreviewGenerator}
     * ({@code sectionCount <= 1}) actually does something: without it, this document's savings
     * section legitimately declaring zero activity would make {@code ExtractionCheck} report the
     * WHOLE document as "nothing to import" -- masking the credit-card section's real, unrelated
     * extraction failure behind a message that says nothing is wrong with the file.
     */
    public static byte[] buildExplicitZeroTransactionCountInACompositeStatementSample() throws IOException {
        float[] savingsCol = {LEFT_MARGIN, 150f, 320f, 400f, 480f};
        float[] ccCol = {LEFT_MARGIN, 150f, 470f};

        PageBuilder page = new PageBuilder();
        page.line("Composite Statement")
                .blankLine()
                .line("SAVINGS ACCOUNT-RES  100-111111-002")
                .row(savingsCol, "Date", "Transaction Details", "Deposits", "Withdrawals", "Balance")
                .row(savingsCol, "01 Jul 2026", "BALANCE BROUGHT FORWARD", null, null, "500.00")
                .row(savingsCol, "01 Jul 2026", "CLOSING BALANCE", null, null, "500.00")
                .row(savingsCol, "01 Jul 2026", "Transaction Turnover", "0.00", "0.00", "500.00")
                .row(savingsCol, "01 Jul 2026", "Transaction Count", "0", "0", "500.00")
                .blankLine()
                .line("CREDIT CARD ACCOUNT  4000 1111 2222 3333")
                .row(ccCol, "DATE", "TRANSACTION DETAILS", "AMOUNT (Rs.)")
                // A recognized amount column present and non-blank, but unparseable as a number --
                // a genuine, unrelated extraction defect, nothing to do with a printed zero claim.
                .row(ccCol, "15/07/2026", "UPI-Retailer One", "ERR");

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
                .row(col, "02/07/2026", "UPI-Retailer One Purchase", "1200.00", "48800.00")
                // Continuation line: description-only, no date/amount -- must fold into the row
                // above (WRAPPED_DESCRIPTION), not become its own dropped, dateless row.
                .row(col, null, "(Ref# ORDER-9000001111)", null, null);

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
                .row(dataCol, "02/07/2026", "UPI/DR SAMPLE HEALTH CENTRE", "MEDICAL", "500.00 Dr")
                .line("Page 1 of 2");

        PageBuilder page2 = new PageBuilder();
        page2.row(headerCol, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(dataCol, "10/07/2026", "UPI/SAMPLEB/SAMPLEB.SPAY@SBANKONE", "DEPT STORES", "249.00 Dr");

        return render(List.of(page1, page2));
    }
}
