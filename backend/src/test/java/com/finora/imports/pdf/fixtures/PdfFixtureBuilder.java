package com.finora.imports.pdf.fixtures;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
 * The existing SBI golden fixture (src/test/resources/pdf/sbi_sample_statement.pdf) was built the
 * same low-level way by an external script this repo doesn't carry (see
 * PdfPreviewGeneratorTest's own doc comment) -- that fixture's test already proves this technique
 * produces per-cell PositionedText runs granular enough for PdfTableLocator's nearest-x column
 * bucketing to work (its debit-vs-credit-by-position assertion would fail outright if PDFBox's
 * writeString() callback fired once per whole line instead of once per cell). This class exists so
 * new fixtures are regenerable from committed Java source instead of another un-reproducible
 * binary.
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
     * A credit-card statement layout where a single Amount column carries a trailing Dr/Cr
     * suffix (no separate Type/Credit column), no running balance, and the header row repeats
     * verbatim on a second page -- modeled on Axis Bank's "Neo Rupay" statement, but not specific
     * to it; any bank/card issuer could ship this same column shape.
     */
    public static byte[] buildDrCrSuffixAmountColumnSample() throws IOException {
        float[] col = {LEFT_MARGIN, 110f, 350f, 470f};

        PageBuilder page1 = new PageBuilder();
        page1.line("AXIS BANK")
                .line("Neo Rupay Credit Card Statement")
                .line("Total Payment Due 27,665.16 Dr Minimum Payment Due 577.00 Dr")
                .blankLine()
                .row(col, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(col, "24/06/2026", "UPI/TOBOX VENTURES PRIVATE L/GOKHANA.PAYU@AXISB", "MISC STORE", "37.94 Dr")
                .row(col, "25/06/2026", "UPI/MANKAR DOSA/PAYTM.S27A881@PTY", "RESTAURANTS", "150.00 Dr")
                .row(col, "30/06/2026", "BBPS PAYMENT RECEIVED - DP016181142205FXN9QZ", "", "10,081.99 Cr");

        PageBuilder page2 = new PageBuilder();
        // Same header repeated verbatim on the second page -- PdfTableLocator must recognize this
        // as "more of the same table," not a new section, and must not stage it as a data row.
        page2.row(col, "DATE", "TRANSACTION DETAILS", "MERCHANT CATEGORY", "AMOUNT (Rs.)")
                .row(col, "10/07/2026", "UPI/BLINKIT/BLINKIT.PAYU@HDFCBANK", "DEPT STORES", "249.00 Dr")
                .row(col, "13/07/2026", "UPI/MYNTRA DESIGNS PRIVATE L/MYNTRA1ONLINE.GPAY", "MISC STORE", "496.00 Dr");

        return render(List.of(page1, page2));
    }

    /**
     * A credit-card statement layout with a combined date-and-time column, a leading "+" marking
     * a credit with no marker at all on a debit row, and a transaction's description that can
     * continue onto a second, dateless/amountless visual row which must fold into the row above
     * it -- modeled on HDFC's "Tata Neu Plus" statement, but not specific to it.
     */
    public static byte[] buildWrappedDescriptionCreditCardSample() throws IOException {
        float[] col = {LEFT_MARGIN, 150f, 400f, 470f, 530f};

        PageBuilder page = new PageBuilder();
        page.line("HDFC BANK")
                .line("Tata Neu Plus HDFC Bank Credit Card Statement")
                .line("Total Amount Due 1,817.00 Minimum Due 200.00")
                .blankLine()
                .row(col, "DATE & TIME", "TRANSACTION DESCRIPTION", "Base NeuCoins", "AMOUNT", "PI")
                .row(col, "30/06/2026 14:18", "BPPY CC PAYMENT DP016181141814AHOaZ", "", "+440.00", "l")
                // Continuation line: description-only, no date, no amount -- must fold into the
                // row above rather than becoming its own dropped, dateless row.
                .row(col, null, "(Ref# ST261820084000010394028)", null, null, null)
                .row(col, "11/07/2026 19:34", "UPI-Amazon India", "", "1,817.02", "l");

        return render(List.of(page));
    }

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
}
