package com.finora.imports.analysis;

import com.finora.imports.pdf.fixtures.ScannedPdfFixture;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MEASUREMENT-ONLY corpus for Track A pass 2. Not production test infrastructure, not part of the
 * Capability Registry, and deliberately NOT added to {@code PdfFixtureBuilder} -- that class's own
 * doc comment states its index "only ever grows when a REAL document motivates a new fixture --
 * never speculatively", and these fixtures are authored to fill measurement gaps, not to protect a
 * capability against a real document. Mixing the two would corrupt that policy.
 *
 * <h2>Fidelity disclosure -- read before believing any number derived from this file</h2>
 *
 * NONE of these are real bank documents. Every one is REALISTIC-SYNTHETIC: the column vocabulary
 * and layout conventions are copied from bank layouts already named in this repository's own
 * production comments (Axis, ICICI, SBI, Union Bank of India, Canara, Kotak, HSBC, PNB, Bandhan,
 * HDFC, Bank of Baroda), but the geometry is authored here rather than captured. That means:
 *
 * <ul>
 *   <li>Header VOCABULARY measured from these is only as good as the source comments -- it can
 *       confirm that a named real string is or is not covered by a hint list, and it cannot
 *       discover a string nobody has written down.</li>
 *   <li>Header GEOMETRY measured from these proves nothing about real documents. PDFBox run
 *       fragmentation, kerning, centered multi-tier headings and column drift are exactly what a
 *       hand-built fixture reconstructs wrongly. Where a fixture reproduces a geometric failure it
 *       does so because it was CONSTRUCTED to; that is a demonstration, not a rate.</li>
 * </ul>
 *
 * Ground truth for every fixture is declared alongside it in {@link Spec}, from the construction
 * code, never from any parser's output.
 */
public final class Pass2CorpusFixtures {

    private static final float FONT_SIZE = 9f;
    private static final float LEFT = 40f;
    private static final float ROW_H = 10f;
    private static final float TOP_Y = 770f;
    private static final PDType1Font HELVETICA = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private Pass2CorpusFixtures() {}

    // ---------------------------------------------------------------- ground truth declaration

    /**
     * What was PLACED in the document, declared by the author. This is the ground truth the
     * measurement harness grades against; the locator is never asked what it thinks is there.
     *
     * @param id              fixture identifier
     * @param category        which of the 8 PM-required categories this fills
     * @param modelledOn      the real layout convention it imitates (never a real document)
     * @param ledgerHeaders   how many ledger header ROW INSTANCES were placed (repeats included)
     * @param ledgerHeaderCells the ledger header's cells, verbatim as placed
     * @param ledgerDataRows  how many transaction rows were placed
     * @param nonLedgerTables non-ledger tables placed, name -> header cells as placed
     * @param note            what this fixture does and does not prove
     */
    public record Spec(String id, String category, String modelledOn,
                       int ledgerHeaders, List<String> ledgerHeaderCells, int ledgerDataRows,
                       Map<String, List<String>> nonLedgerTables, String note) {}

    public record Fixture(Spec spec, byte[] bytes) {}

    // ---------------------------------------------------------------- tiny renderer

    private record Cell(float x, String text) {}
    private record Line(float y, List<Cell> cells) {}

    private static final float LANDSCAPE_TOP_Y = 560f;

    private static final class Page {
        final List<Line> lines = new ArrayList<>();
        float y;

        Page() { this(TOP_Y); }

        Page(float top) { this.y = top; }

        Page text(String s) {
            lines.add(new Line(y, List.of(new Cell(LEFT, s))));
            y -= ROW_H;
            return this;
        }

        Page row(float[] col, String... values) {
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < values.length && i < col.length; i++) {
                if (values[i] != null && !values[i].isEmpty()) cells.add(new Cell(col[i], values[i]));
            }
            lines.add(new Line(y, cells));
            y -= ROW_H;
            return this;
        }

        /** A row whose listed columns are RIGHT-aligned to the given right edges -- how every real
         *  statement sets an amount column, and the precondition for the collision in category 7. */
        Page rightAlignedRow(float[] leftCol, float[] rightEdges, String... values) {
            List<Cell> cells = new ArrayList<>();
            for (int i = 0; i < values.length; i++) {
                if (values[i] == null || values[i].isEmpty()) continue;
                if (i < rightEdges.length && rightEdges[i] > 0) {
                    cells.add(new Cell(rightEdges[i] - widthOf(values[i]), values[i]));
                } else if (i < leftCol.length) {
                    cells.add(new Cell(leftCol[i], values[i]));
                }
            }
            lines.add(new Line(y, cells));
            y -= ROW_H;
            return this;
        }

        Page gap() {
            y -= ROW_H;
            return this;
        }
    }

    private static float widthOf(String s) {
        try {
            return HELVETICA.getStringWidth(s) / 1000f * FONT_SIZE;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] render(List<Page> pages) throws IOException {
        return render(pages, false);
    }

    /** Landscape where a layout genuinely needs the width -- a combined statement with eight
     *  currency-qualified headings does not fit A4 portrait at 9pt, and real banks print those
     *  landscape. Forcing them onto portrait would manufacture column collisions that are an
     *  artefact of this file rather than a property of the layout. */
    private static byte[] render(List<Page> pages, boolean landscape) throws IOException {
        PDRectangle size = landscape
                ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth())
                : PDRectangle.A4;
        try (PDDocument doc = new PDDocument()) {
            for (Page page : pages) {
                PDPage pdPage = new PDPage(size);
                doc.addPage(pdPage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, pdPage)) {
                    cs.setFont(HELVETICA, FONT_SIZE);
                    for (Line line : page.lines) {
                        for (Cell cell : line.cells()) {
                            cs.beginText();
                            cs.newLineAtOffset(cell.x(), line.y());
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

    // ================================================================================
    // CATEGORY 1 -- additional bank layouts (realistic-synthetic, 6 new layouts)
    // ================================================================================

    /** Axis Bank savings export convention: "Tran Date / Chq No / Particulars / Debit / Credit /
     *  Balance / Init.Br" -- the seven-column shape, with the branch-code trailing column that no
     *  hint list knows about. Axis is named in PdfTableLocator's STATEMENT_CLOSING_MARKER and
     *  MAX_LEADING_CONTINUATION_ROWS comments as a real analysed layout. */
    public static Fixture axisSavingsLedger() throws IOException {
        float[] col = {LEFT, 110f, 160f, 320f, 380f, 440f, 505f};
        List<String> header = List.of("Tran Date", "Chq No", "Particulars", "Debit", "Credit",
                "Balance", "Init.Br");
        Page p = new Page();
        p.text("Statement of Account")
                .text("Account No 900010012345678   Branch KORAMANGALA") // synthetic-ok: placeholder account number, matches Axis's 15-digit format only
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "01-07-2026", "", "SALARY JUL2026", "", "62000.00", "62000.00", "KRM")
                .row(col, "03-07-2026", "", "UPI/P2M/SAMPLE STORE", "1249.00", "", "60751.00", "KRM")
                .row(col, "09-07-2026", "445512", "CHQ PAID SELF", "5000.00", "", "55751.00", "KRM")
                .row(col, "18-07-2026", "", "NEFT CR SAMPLE PVT LTD", "", "8400.00", "64151.00", "KRM")
                .text("**** End of Statement ****");
        return new Fixture(new Spec("axis-savings-ledger", "1-more-banks", "Axis Bank savings export",
                1, header, 4, Map.of(),
                "Adds 'tran date', 'chq no', 'particulars', 'init.br' to the observed vocabulary."), render(List.of(p)));
    }

    /** ICICI internet-banking export convention: a serial-number column first, BOTH a value date
     *  and a transaction date, and currency-qualified amount headings with the space-before-paren
     *  spacing ICICI actually prints ("Withdrawal Amount (INR )"). */
    public static Fixture iciciSerialNumberedLedger() throws IOException {
        float[] col = {LEFT, 70f, 135f, 215f, 290f, 430f, 555f, 680f};
        List<String> header = List.of("S No.", "Value Date", "Transaction Date", "Cheque Number",
                "Transaction Remarks", "Withdrawal Amount (INR )", "Deposit Amount (INR )",
                "Balance (INR )");
        Page p = new Page(LANDSCAPE_TOP_Y);
        p.text("Detailed Statement")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "1", "02/07/2026", "02/07/2026", "", "MMT/IMPS/618312/SAMPLE", "", "15000.00", "78000.00")
                .row(col, "2", "06/07/2026", "06/07/2026", "", "BIL/ONL/000123/SAMPLEUTIL", "2310.00", "", "75690.00")
                .row(col, "3", "14/07/2026", "14/07/2026", "", "UPI/618900/SAMPLE", "640.00", "", "75050.00");
        return new Fixture(new Spec("icici-serial-ledger", "1-more-banks", "ICICI Bank internet-banking export",
                1, header, 3, Map.of(),
                "Adds 's no.', 'value date', 'transaction date', 'cheque number', "
                        + "'transaction remarks', and the ' (INR )' spacing variant."), render(List.of(p), true));
    }

    /** SBI (YONO/onlinesbi) export convention: separate Txn Date and Value Date, a combined
     *  "Ref No./Cheque No." column, and plain Debit/Credit. The existing corpus already holds an
     *  SBI-SHAPED golden; this differs from it by carrying the ref column and the second date. */
    public static Fixture sbiRefNumberLedger() throws IOException {
        float[] col = {LEFT, 100f, 165f, 290f, 400f, 460f, 520f};
        List<String> header = List.of("Txn Date", "Value Date", "Description", "Ref No./Cheque No.",
                "Debit", "Credit", "Balance");
        Page p = new Page();
        p.text("Account Statement")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "1 Jul 2026", "1 Jul 2026", "BY TRANSFER-NEFT", "SBIN126183", "", "22000.00", "44000.00")
                .row(col, "7 Jul 2026", "7 Jul 2026", "TO TRANSFER-UPI", "618312445", "1899.00", "", "42101.00")
                .row(col, "21 Jul 2026", "21 Jul 2026", "ATM WDL", "S1K618", "3000.00", "", "39101.00");
        return new Fixture(new Spec("sbi-ref-ledger", "1-more-banks", "SBI onlinesbi/YONO export",
                1, header, 3, Map.of(),
                "Adds 'ref no./cheque no.' and the 'd MMM yyyy' date shape."), render(List.of(p)));
    }

    /** Union Bank of India convention: one signed Amount column plus a separate Dr/Cr column, and
     *  the "Amount(Rs)" currency form. Union Bank is named in PdfTableLocator's PAGE_FOOTER comment
     *  and in CsvParser.normalizeHeaderCell's own bug note about "Amount(" splitting. */
    public static Fixture unionBankSingleAmountLedger() throws IOException {
        float[] col = {LEFT, 75f, 140f, 240f, 400f, 470f, 530f};
        List<String> header = List.of("S.No", "Date", "Transaction Id", "Remarks", "Amount(Rs)",
                "Balance(Rs)", "Dr/Cr");
        Page p = new Page();
        p.text("Savings Account,")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "1", "05-07-2026", "S61831245", "NEFT INWARD SAMPLE", "18298.00", "36298.00", "Cr")
                .row(col, "2", "11-07-2026", "S61833901", "ATM CASH WDL", "500.00", "35798.00", "Dr")
                .row(col, "3", "23-07-2026", "S61840012", "POS PURCHASE", "1240.50", "34557.50", "Dr")
                .text("Page 1 of 1");
        return new Fixture(new Spec("union-bank-single-amount-ledger", "1-more-banks",
                "Union Bank of India net-banking export",
                1, header, 3, Map.of(),
                "Adds 's.no', 'transaction id', 'dr/cr' and the 'Amount(Rs)' currency form."),
                render(List.of(p)));
    }

    /** Canara Bank convention: the "Reference / Cheque No." column that TransactionNormalizer's
     *  REFERENCE_HINTS comment names as a real, previously-discarded column. */
    public static Fixture canaraReferenceLedger() throws IOException {
        float[] col = {LEFT, 105f, 175f, 290f, 400f, 460f, 520f};
        List<String> header = List.of("Txn Date", "Value Date", "Reference / Cheque No.",
                "Description", "Debit", "Credit", "Balance");
        Page p = new Page();
        p.text("Statement of Account")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "02/07/2026", "02/07/2026", "618312", "UPI SAMPLE PAYEE", "760.00", "", "21240.00")
                .row(col, "12/07/2026", "12/07/2026", "618455", "SALARY CREDIT", "", "31000.00", "52240.00")
                .text("page 1");
        return new Fixture(new Spec("canara-reference-ledger", "1-more-banks", "Canara Bank export",
                1, header, 2, Map.of(),
                "Adds 'reference / cheque no.' as a ledger column."), render(List.of(p)));
    }

    /** HSBC composite-statement convention: deposits BEFORE withdrawals (the reverse of HDFC's
     *  order) and "Transaction details" as the narration heading. HSBC's composite statement is
     *  named in PdfTableLocator's own class doc comment. */
    public static Fixture hsbcDepositsFirstLedger() throws IOException {
        float[] col = {LEFT, 110f, 340f, 410f, 480f};
        List<String> header = List.of("Date", "Transaction details", "Deposits", "Withdrawals",
                "Balance");
        Page p = new Page();
        p.text("SAVINGS ACCOUNT-RES  100-111111-002")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "04 Jul 26", "CREDIT INTEREST", "112.40", "", "19112.40")
                .row(col, "15 Jul 26", "STANDING ORDER SAMPLE", "", "2500.00", "16612.40")
                .row(col, "28 Jul 26", "INWARD REMITTANCE", "40000.00", "", "56612.40");
        return new Fixture(new Spec("hsbc-deposits-first-ledger", "1-more-banks",
                "HSBC composite statement savings section",
                1, header, 3, Map.of(),
                "Adds 'transaction details' as narration and the deposits-before-withdrawals order."),
                render(List.of(p)));
    }

    // ================================================================================
    // CATEGORY 2 -- footnote-marker header variants
    // ================================================================================

    /**
     * A ledger whose every meaningful heading carries a footnote marker, of five distinct shapes
     * seen or plausible in the real corpus: {@code **}, {@code *}, {@code #}, {@code ^}, and a
     * SPACE-separated marker ({@code Balance ***}). Measures the pass-1 gap further: the locator's
     * per-word matcher tolerates these; {@code CsvParser.normalizeHeaderCell} strips only
     * {@code . , ; :} so {@code hasHeaderMatch}/{@code firstNonBlank} do not.
     *
     * <p>Markers are placed on BOTH sides of the whitespace boundary deliberately -- an attached
     * marker ("balance**") and a detached one ("balance ***") are different problems for a
     * whole-cell exact matcher and for a per-word one, and pass 1 only observed attached ones.
     */
    public static Fixture footnoteMarkedLedger() throws IOException {
        float[] col = {LEFT, 110f, 300f, 380f, 460f};
        List<String> header = List.of("Txn Date#", "Narration^", "Withdrawal Amt.*",
                "Deposit Amt.*", "Closing Balance**");
        Page p = new Page();
        p.text("Statement of Account")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "01/07/2026", "SALARY CREDIT", "", "50000.00", "50000.00")
                .row(col, "05/07/2026", "GROCERY", "1200.00", "", "48800.00")
                .gap()
                .text("* excludes charges   ** as at close of business   # posting date   ^ as printed");
        return new Fixture(new Spec("footnote-marked-ledger", "2-footnote-markers",
                "HDFC composite p13 'Xxxxxxx balance**' pattern, generalised",
                1, header, 2, Map.of(),
                "Every heading carries an ATTACHED marker. Measures locator-vs-CsvParser divergence."),
                render(List.of(p)));
    }

    /** Same gap, DETACHED markers (a space before the marker) and a second marker vocabulary
     *  ({@code (a)}, {@code 1)}) that a per-word matcher must survive differently -- a detached
     *  marker becomes its own WORD, which changes both matchers' behaviour, not just one. */
    public static Fixture detachedFootnoteMarkedLedger() throws IOException {
        float[] col = {LEFT, 110f, 300f, 380f, 460f};
        List<String> header = List.of("Date 1)", "Description (a)", "Debit **", "Credit **",
                "Balance ***");
        Page p = new Page();
        p.text("Statement of Account")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "02/07/2026", "NEFT INWARD", "", "12000.00", "62000.00")
                .row(col, "08/07/2026", "CARD PAYMENT", "3400.00", "", "58600.00");
        return new Fixture(new Spec("detached-footnote-marked-ledger", "2-footnote-markers",
                "footnote markers printed detached from their heading",
                1, header, 2, Map.of(),
                "Markers are separate WORDS, not attached suffixes -- a different case for both matchers."),
                render(List.of(p)));
    }

    // ================================================================================
    // CATEGORY 3 -- non-ledger statement sections, FULLY LEGIBLE
    // ================================================================================

    /**
     * The category pass 1 could measure least (29% of header cells legible, 19 masked). Every
     * heading here is written in full -- including the strings {@code PdfTraceRedactor} destroyed
     * in the real trace and named in its own doc comment ("Principal", "Maturity", "Rate Of
     * Interest"). Six non-ledger tables in one document, none of which is a transaction ledger.
     *
     * <p>What this DOES prove: whether the hint lists contain these words. What it does NOT prove:
     * that these are the words real banks print -- they are reconstructions of masked cells,
     * inferred in pass 1 from position and confirmed by nobody.
     */
    public static Fixture nonLedgerSectionsLegible() throws IOException {
        float[] fd = {LEFT, 120f, 200f, 300f, 400f, 490f, 570f, 665f};
        float[] rd = {LEFT, 120f, 210f, 300f, 410f, 520f, 600f};
        float[] loan = {LEFT, 130f, 190f, 300f, 410f, 510f, 620f};
        float[] sum4 = {LEFT, 160f, 290f, 420f};
        float[] sum2 = {LEFT, 160f};
        float[] nom = {LEFT, 170f, 330f};
        float[] tds = {LEFT, 150f, 280f, 400f};

        List<String> fdHeader = List.of("FD Number", "Currency Code", "Deposit Principal",
                "Open/Value Date", "Rate Of Interest", "Maturity Date", "Maturity Amount",
                "Nomination Registered");
        List<String> rdHeader = List.of("Account Number", "Instalment Amount", "Instalment Due Date",
                "Total No of Instalments", "No of Instalments Paid", "Rate Of Interest",
                "Outstanding Balance");
        List<String> loanHeader = List.of("Instalment Number", "Due Date", "Principal Component",
                "Interest Component", "Instalment Amount", "Outstanding Principal", "Status");
        List<String> summaryHeader = List.of("Opening Balance", "Debit Amount", "Credit Amount",
                "Closing Balance");
        List<String> countHeader = List.of("Debit Count", "Credit Count");
        List<String> nomineeHeader = List.of("FD Number", "Nominee Name", "Relationship");
        List<String> tdsHeader = List.of("Quarter", "Interest Paid", "Tax Deducted",
                "Certificate Number");

        Page p1 = new Page(LANDSCAPE_TOP_Y);
        p1.text("Combined Statement of Accounts")
                .gap()
                .row(sum4, summaryHeader.toArray(new String[0]))
                .row(sum4, "24818.22", "3404.91", "55000.00", "76413.31")
                .row(sum2, countHeader.toArray(new String[0]))
                .row(sum2, "2", "1")
                .gap()
                .text("FIXED DEPOSIT - 30000000000001")
                .row(fd, fdHeader.toArray(new String[0]))
                .row(fd, "50300000012345", "INR", "100000.00", "12/01/2026", "7.10", "12/01/2027", "107100.00", "Yes")
                .row(fd, "50300000012346", "INR", "250000.00", "03/03/2026", "7.25", "03/03/2028", "287500.00", "Yes")
                .gap()
                .row(nom, nomineeHeader.toArray(new String[0]))
                .row(nom, "50300000012345", "Sample Nominee One", "Spouse");

        Page p2 = new Page(LANDSCAPE_TOP_Y);
        p2.text("RECURRING DEPOSIT - 30000000000003")
                .row(rd, rdHeader.toArray(new String[0]))
                .row(rd, "60300000098765", "5000.00", "05/08/2026", "24", "7", "6.90", "35000.00")
                .gap()
                .text("Instalment Schedule")
                .row(loan, loanHeader.toArray(new String[0]))
                .row(loan, "1", "05/02/2026", "4712.00", "288.00", "5000.00", "30288.00", "Paid")
                .row(loan, "2", "05/03/2026", "4739.00", "261.00", "5000.00", "25549.00", "Paid")
                .row(loan, "3", "05/04/2026", "4766.00", "234.00", "5000.00", "20783.00", "Due")
                .gap()
                .text("Interest and Tax Summary")
                .row(tds, tdsHeader.toArray(new String[0]))
                .row(tds, "Q1 2026-27", "1840.00", "184.00", "TDSQ1618312");

        Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("statement-summary", summaryHeader);
        tables.put("summary-counts", countHeader);
        tables.put("fd-schedule", fdHeader);
        tables.put("nominee", nomineeHeader);
        tables.put("rd-summary", rdHeader);
        tables.put("instalment-schedule", loanHeader);
        tables.put("tds-summary", tdsHeader);

        return new Fixture(new Spec("non-ledger-sections-legible", "3-non-ledger",
                "HDFC combined statement's FD/RD/summary sections, headings written out in full",
                0, List.of(), 0, tables,
                "No transaction ledger at all -- seven non-ledger tables. Reconstructs the strings "
                        + "the real trace's redactor destroyed; those strings are INFERRED, not observed."),
                render(List.of(p1, p2), true));
    }

    // ================================================================================
    // CATEGORY 4 -- scanned / image-only
    // ================================================================================

    /**
     * A genuinely text-layer-free document: {@link #axisSavingsLedger()} rasterised by the
     * repository's existing {@link ScannedPdfFixture}. What it actually tests is exactly one thing:
     * that native extraction recovers ZERO runs and therefore no table can be located.
     *
     * <p>What a real scanned statement would additionally present and this does NOT: sensor noise,
     * JPEG artefacts, page skew and rotation, uneven illumination, bleed-through from the reverse
     * side, staple/fold shadows, a photographed (not flatbed) page with perspective, handwriting,
     * stamps, and the vastly lower effective resolution of a phone capture. A recogniser that
     * handles this fixture is not thereby shown to handle any of those.
     */
    public static Fixture scannedLedger() throws IOException {
        Fixture native_ = axisSavingsLedger();
        return new Fixture(new Spec("scanned-axis-ledger", "4-scanned",
                "Axis savings ledger, rasterised at 150 DPI with no text layer",
                1, native_.spec().ledgerHeaderCells(), 4, Map.of(),
                "Tests 'no extractable text' ONLY. Not a proxy for real scan degradation."),
                ScannedPdfFixture.scan(native_.bytes()));
    }

    // ================================================================================
    // CATEGORY 5 -- extraction succeeds, a load-bearing field is genuinely missing
    // ================================================================================

    /**
     * Distinct from {@code PdfFixtureBuilder.buildReconciledSummaryNoBalanceColumnSample}, which
     * omits the BALANCE column. Here the balance column is present and the AMOUNT columns are
     * absent entirely: the ledger states date, narration and a running balance, and the amount of
     * each transaction exists only as the difference between consecutive balances. Native
     * extraction succeeds completely and every placed cell is recovered.
     */
    public static Fixture missingAmountColumnsLedger() throws IOException {
        float[] col = {LEFT, 110f, 430f};
        List<String> header = List.of("Date", "Narration", "Balance");
        Page p = new Page();
        p.text("Passbook Statement")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "01/07/2026", "OPENING", "10000.00")
                .row(col, "04/07/2026", "UPI SAMPLE PAYEE", "8750.00")
                .row(col, "12/07/2026", "SALARY CREDIT", "48750.00")
                .row(col, "26/07/2026", "CARD PAYMENT", "45300.00");
        return new Fixture(new Spec("missing-amount-columns-ledger", "5-missing-field",
                "a passbook-style print with no debit/credit columns at all",
                1, header, 4, Map.of(),
                "Load-bearing field absent = TRANSACTION_AMOUNT. Everything placed is extracted."),
                render(List.of(p)));
    }

    /**
     * A second variant of the same class, where the missing field is the DATE's year: the ledger
     * prints "04/07" with the year stated only in the statement-period line above the table.
     * Extraction is complete; the field is present but not load-bearing on its own.
     */
    public static Fixture yearlessDateLedger() throws IOException {
        float[] col = {LEFT, 100f, 330f, 400f, 470f};
        List<String> header = List.of("Date", "Particulars", "Debit", "Credit", "Balance");
        Page p = new Page();
        p.text("Statement for the period 01 Jul 2026 to 31 Jul 2026")
                .gap()
                .row(col, header.toArray(new String[0]))
                .row(col, "01/07", "OPENING BALANCE", "", "", "10000.00")
                .row(col, "04/07", "UPI SAMPLE PAYEE", "1250.00", "", "8750.00")
                .row(col, "12/07", "SALARY CREDIT", "", "40000.00", "48750.00");
        return new Fixture(new Spec("yearless-date-ledger", "5-missing-field",
                "a ledger printing day/month only, year stated once in the period line",
                1, header, 3, Map.of(),
                "The field is present but incomplete -- a different shape from an absent column."),
                render(List.of(p)));
    }

    // ================================================================================
    // CATEGORY 6 -- headers visually present, not extractable
    // ================================================================================

    /**
     * The heading row is drawn as an IMAGE; the data rows are ordinary text. A human reading the
     * page sees a complete table; {@code PdfTextExtractor} recovers the data rows and no heading.
     * This is the genuine article for this category, not an approximation: the header really is
     * visible and really is unextractable.
     */
    public static Fixture imageOnlyHeaderLedger() throws IOException {
        float[] col = {LEFT, 110f, 300f, 380f, 460f};
        List<String> header = List.of("Date", "Narration", "Withdrawals", "Deposits", "Balance");

        // Step 1: a page carrying ONLY the heading row, at the position it will occupy.
        Page headerOnly = new Page();
        headerOnly.gap().gap().row(col, header.toArray(new String[0]));
        byte[] headerPdf = render(List.of(headerOnly));

        // Step 2: rasterise it, then draw the raster and the data rows into one page.
        try (PDDocument src = org.apache.pdfbox.Loader.loadPDF(headerPdf);
             PDDocument out = new PDDocument()) {
            BufferedImage raster = new PDFRenderer(src).renderImageWithDPI(0, 150);
            PDPage page = new PDPage(PDRectangle.A4);
            out.addPage(page);
            PDImageXObject img = LosslessFactory.createFromImage(out, raster);
            try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                cs.drawImage(img, 0, 0, PDRectangle.A4.getWidth(), PDRectangle.A4.getHeight());
                cs.setFont(HELVETICA, FONT_SIZE);
                String[][] data = {
                        {"01/07/2026", "SALARY CREDIT", "", "50000.00", "50000.00"},
                        {"05/07/2026", "GROCERY STORE", "1200.00", "", "48800.00"},
                        {"19/07/2026", "ELECTRICITY BILL", "1404.91", "", "47395.09"}};
                float y = TOP_Y - 3 * ROW_H;
                for (String[] rowValues : data) {
                    for (int i = 0; i < rowValues.length; i++) {
                        if (rowValues[i].isEmpty()) continue;
                        cs.beginText();
                        cs.newLineAtOffset(col[i], y);
                        cs.showText(rowValues[i]);
                        cs.endText();
                    }
                    y -= ROW_H;
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            out.save(bytes);
            return new Fixture(new Spec("image-only-header-ledger", "6-unextractable-header",
                    "a bank template whose column band is a graphic",
                    1, header, 3, Map.of(),
                    "Header visible to a reader, absent from the text layer. Data rows fully extractable."),
                    bytes.toByteArray());
        }
    }

    /**
     * Second shape of the same category, geometric rather than graphical: the heading row is split
     * across FOUR tiers spaced 20pt apart -- past {@code HEADER_WRAP_MAX_GAP} (12pt), which is the
     * exact measured cause of the real HDFC p10 failure in pass 1. The header text IS in the text
     * layer; reconstruction is what cannot assemble it.
     */
    public static Fixture fourTierWideGapHeader() throws IOException {
        float[] col = {LEFT, 130f, 240f, 350f, 460f};
        Page p = new Page();
        p.text("Deposit Schedule")
                .gap()
                .row(col, "Deposit", "Deposit", "Rate Of", "Maturity", "Maturity")
                .gap()
                .row(col, "Number", "Principal", "Interest", "Date", "Amount")
                .gap()
                .row(col, "50300000012345", "100000.00", "7.10", "12/01/2027", "107100.00")
                .row(col, "50300000012346", "250000.00", "7.25", "03/03/2028", "287500.00");
        return new Fixture(new Spec("four-tier-wide-gap-header", "6-unextractable-header",
                "HDFC p10 FD schedule's tier spacing, reproduced deliberately",
                0, List.of(), 0,
                Map.of("fd-schedule-2-tier", List.of("Deposit Number", "Deposit Principal",
                        "Rate Of Interest", "Maturity Date", "Maturity Amount")),
                "Tiers 20pt apart, past HEADER_WRAP_MAX_GAP=12. Text present, assembly impossible."),
                render(List.of(p)));
    }

    // ================================================================================
    // CATEGORY 7 -- merged amount columns
    // ================================================================================

    /**
     * The real HDFC artefact reproduced at the extraction level: two amounts arriving as ONE text
     * run ({@code "0.00 96,142.00"}) at a single x, exactly as pass 1 observed on
     * {@code hdfc-txn-date-narration-header}. Placed as one run deliberately -- the run really is
     * one run in the real document, and no downstream rule can un-merge what extraction merged.
     */
    public static Fixture mergedAmountSingleRun() throws IOException {
        float[] col = {LEFT, 110f, 300f, 380f, 460f};
        List<String> header = List.of("Txn Date", "Narration", "Withdrawals", "Deposits",
                "Closing Balance");
        Page p = new Page();
        p.text("Statement of Account")
                .gap()
                .row(col, header.toArray(new String[0]))
                // Both amount values arrive as ONE run in the Deposits column.
                .row(col, "01/07/2026", "SALARY CREDIT", "", "0.00 96,142.00", "96142.00")
                .row(col, "05/07/2026", "GROCERY", "436.00", "0.00", "95706.00")
                // A three-way merge: withdrawal, deposit and balance in one cell.
                .row(col, "11/07/2026", "NEFT OUTWARD", "", "", "20.00 0.00 95686.00");
        return new Fixture(new Spec("merged-amount-single-run", "7-merged-amounts",
                "HDFC 'Deposits=\"0.00 96,142.00\"' bucketing, as a pre-merged run",
                1, header, 3, Map.of(),
                "Rows 1 and 3 carry pre-merged amount cells by construction; row 2 is the control."),
                render(List.of(p)));
    }

    /**
     * The same failure CLASS produced by geometry rather than by a pre-merged run: the two amount
     * columns are right-aligned 14pt apart, and one row's Withdrawals value is short enough
     * ({@code 0.00}) that its LEFT edge crosses the midpoint into Deposits -- the precise
     * mechanism {@code RIGHT_ALIGNED_AMOUNTS} exists to correct.
     *
     * <p>Unlike a replayed v1 trace, this fixture carries real measured widths, so the correction
     * is REACHABLE here. That is the point: pass 1 could not test it at all.
     */
    public static Fixture rightAlignedAmountCollision() throws IOException {
        float[] leftCol = {LEFT, 110f, 300f, 360f, 430f};
        float[] rightEdges = {0f, 0f, 348f, 394f, 500f};
        List<String> header = List.of("Txn Date", "Narration", "Withdrawals", "Deposits",
                "Closing Balance");
        Page p = new Page();
        p.text("Statement of Account")
                .gap()
                .row(leftCol, header.toArray(new String[0]))
                .rightAlignedRow(leftCol, rightEdges, "01/07/2026", "LONG WITHDRAWAL", "436.00", "0.00", "49564.00")
                .rightAlignedRow(leftCol, rightEdges, "05/07/2026", "SHORT WITHDRAWAL", "20.00", "0.00", "49544.00")
                .rightAlignedRow(leftCol, rightEdges, "09/07/2026", "SHORTEST WITHDRAWAL", "0.00", "0.00", "49544.00")
                .rightAlignedRow(leftCol, rightEdges, "14/07/2026", "DEPOSIT ROW", "0.00", "25000.00", "74544.00");
        return new Fixture(new Spec("right-aligned-amount-collision", "7-merged-amounts",
                "the real HDFC x=333.43/337.87/342.32 vs midpoint 340.88 collision",
                1, header, 4,
                Map.of(),
                "Ground truth per row is declared in the harness: withdrawal, deposit, balance."),
                render(List.of(p)));
    }

    // ================================================================================
    // CATEGORY 8 -- dormant / zero-activity account
    // ================================================================================

    /**
     * An account with genuinely no transactions in the period. The header is present, the ledger
     * body is a single stated "no transactions" line, and the summary reports opening = closing
     * with zero counts. A correct extraction of this document produces zero transactions, and that
     * is the right answer -- the shape a sufficiency check must not confuse with a failed read.
     */
    public static Fixture dormantAccountStatement() throws IOException {
        float[] col = {LEFT, 110f, 300f, 380f, 460f};
        float[] sum4 = {LEFT, 160f, 290f, 420f};
        float[] sum2 = {LEFT, 160f};
        List<String> header = List.of("Date", "Narration", "Withdrawals", "Deposits", "Balance");
        List<String> summaryHeader = List.of("Opening Balance", "Debit Amount", "Credit Amount",
                "Closing Balance");
        List<String> countHeader = List.of("Debit Count", "Credit Count");
        Page p = new Page();
        p.text("Statement of Account")
                .text("Account Status : DORMANT")
                .text("Statement for the period 01 Jul 2026 to 31 Jul 2026")
                .gap()
                .row(sum4, summaryHeader.toArray(new String[0]))
                .row(sum4, "1523.40", "0.00", "0.00", "1523.40")
                .row(sum2, countHeader.toArray(new String[0]))
                .row(sum2, "0", "0")
                .gap()
                .row(col, header.toArray(new String[0]))
                .text("No transactions during this period")
                .gap()
                .text("**** End of Statement ****");
        Map<String, List<String>> tables = new LinkedHashMap<>();
        tables.put("statement-summary", summaryHeader);
        tables.put("summary-counts", countHeader);
        return new Fixture(new Spec("dormant-account-statement", "8-dormant",
                "a dormant savings account's nil-activity statement",
                1, header, 0, tables,
                "Zero transactions is the CORRECT extraction. Opening = closing, both counts zero."),
                render(List.of(p)));
    }

    // ---------------------------------------------------------------- registry

    public static List<Fixture> all() throws IOException {
        List<Fixture> fixtures = new ArrayList<>();
        fixtures.add(axisSavingsLedger());
        fixtures.add(iciciSerialNumberedLedger());
        fixtures.add(sbiRefNumberLedger());
        fixtures.add(unionBankSingleAmountLedger());
        fixtures.add(canaraReferenceLedger());
        fixtures.add(hsbcDepositsFirstLedger());
        fixtures.add(footnoteMarkedLedger());
        fixtures.add(detachedFootnoteMarkedLedger());
        fixtures.add(nonLedgerSectionsLegible());
        fixtures.add(scannedLedger());
        fixtures.add(missingAmountColumnsLedger());
        fixtures.add(yearlessDateLedger());
        fixtures.add(imageOnlyHeaderLedger());
        fixtures.add(fourTierWideGapHeader());
        fixtures.add(mergedAmountSingleRun());
        fixtures.add(rightAlignedAmountCollision());
        fixtures.add(dormantAccountStatement());
        return fixtures;
    }
}
