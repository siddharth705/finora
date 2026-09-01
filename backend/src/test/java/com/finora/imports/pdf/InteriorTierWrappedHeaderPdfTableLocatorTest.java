package com.finora.imports.pdf;

import com.finora.imports.CsvParser;
import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTERIOR_TIER_COLUMNS: a header block whose column GROUPS wrap to UNEQUAL depths -- some
 * columns print across the TOP and BOTTOM lines of a three-line heading, skipping the middle line
 * entirely, while a second group of columns prints ONLY on that middle line and nowhere else.
 *
 * <p>Modeled on a real third-party-generated SBI savings statement (values fully synthetic per
 * the Synthetic Fixture Policy): "Date(Value" / "Ref No." / "Transaction" print on the TOP line,
 * "Particulars" / "Debit(Rs)" / "Credit(Rs)" / "Balance(Rs)" print ONLY on the MIDDLE line, and
 * "Date)" / "/Cheque No" / "Type" print on the BOTTOM line -- which is the only one of the three
 * that scores as a header alone (it has a date-hint cell and a second recognized name, "Type").
 *
 * <p>{@link PdfTableLocator#mergeHeaderLines} cannot represent this at all: it seeds columns from
 * a block's FIRST line and requires every later line's cell to join one of them, refusing the
 * whole merge otherwise (see that method's own doc comment for why that refusal is right in
 * general). The middle line's four cells sit nowhere near the top line's three, so the merge
 * aborted outright and the table located with 2-3 garbled columns instead of 7, staging zero
 * transaction rows while the import reported success.
 */
class InteriorTierWrappedHeaderPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y, float width) {
        return new PositionedText(text, x, y, 0, width);
    }

    /**
     * The real document's shape, reduced to its essentials and rendered at synthetic coordinates:
     * three header lines 8pt apart (well inside {@code HEADER_WRAP_MAX_GAP}), the outer group's
     * two halves 2-3pt off their own anchor (the real document's own wrap offset), and the
     * interior group's four cells sitting far enough from every outer anchor (at least 90pt) that
     * none of them could ever join by the ordinary 40pt {@code HEADER_WRAP_MAX_COLUMN_JOIN} rule.
     */
    private List<PositionedText> documentRuns() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // Top line (y=100): the outer group's upper half. Neither "Value" nor "Reference"
                // is recognized column-name vocabulary on its own -- exactly why this line alone
                // does not score as a header (no HEADER_HINTS/DATE_HINTS match at all).
                run("Value", 50f, 100f, 30f),
                run("Reference", 400f, 100f, 55f),
                run("Transaction", 480f, 100f, 58f),

                // Middle line (y=108): the interior group. Every cell here is, on its own,
                // recognized column-name vocabulary -- the content gate that lets this line
                // contribute brand new columns instead of refusing the merge.
                run("Particulars", 140f, 108f, 50f),
                run("Debit", 200f, 108f, 24f),
                run("Credit", 260f, 108f, 26f),
                run("Balance", 320f, 108f, 32f),

                // Bottom line (y=116): the outer group's lower half. Scores as a header ALONE --
                // "Date" and "Type" are both recognized -- which is what makes this the seed
                // wrappedHeaderAt reaches first under the OLD algorithm, with nothing above it in
                // reach because that algorithm never looks backward.
                run("Date", 52f, 116f, 20f),
                run("Number", 403f, 116f, 32f),
                run("Type", 483f, 116f, 20f)));

        // Two ordinary transactions, a full row pitch below the heading.
        runs.addAll(List.of(
                run("01/07/2026", 51f, 140f, 45f), run("REF00001", 401f, 140f, 40f),
                run("Credit", 481f, 140f, 26f), run("Salary Credit", 141f, 140f, 60f),
                run("5,000.00", 261f, 140f, 38f), run("15,000.00", 321f, 140f, 40f),

                run("02/07/2026", 51f, 160f, 45f), run("REF00002", 401f, 160f, 40f),
                run("Debit", 481f, 160f, 24f), run("Grocery Store", 141f, 160f, 60f),
                run("1,200.00", 201f, 160f, 38f), run("13,800.00", 321f, 160f, 40f)));

        return runs;
    }

    private PdfTableLocator.LocatedDocument locate(DocumentContext ctx) {
        return new PdfTableLocator().locateAll(documentRuns(), ctx);
    }

    @Test
    void theInteriorTierBecomesFourNewColumns_andTheOuterGroupIsStillRenamed() {
        DocumentContext ctx = new DocumentContext("PDF", "InteriorTierWrappedHeaderTest");

        PdfTableLocator.LocatedDocument doc = locate(ctx);

        assertThat(doc.sections()).hasSize(1);
        // The full header, not one row's own keys -- row 1 is a credit transaction with no Debit
        // value at all, so its own map legitimately omits that key (PdfFixtureBuilder.row() never
        // creates a cell for a null/empty value, matching how a real statement's ragged columns
        // work). The header itself still names all seven columns.
        assertThat(ctx.buildMetadata().headers()).containsExactlyInAnyOrder(
                "Value Date", "Particulars", "Debit", "Credit", "Balance",
                "Reference Number", "Transaction Type");
        assertThat(ctx.capabilities()).extracting("capability")
                .contains("WRAPPED_HEADER", "WRAPPED_HEADER_INTERIOR_TIER_COLUMNS");
    }

    @Test
    void bothTransactionsStageWithEveryValueUnderItsOwnColumn() {
        List<Map<String, String>> rows = locate(null).sections().get(0).rows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Value Date", "01/07/2026",
                "Reference Number", "REF00001",
                "Transaction Type", "Credit",
                "Particulars", "Salary Credit",
                "Credit", "5,000.00",
                "Balance", "15,000.00"));
        assertThat(rows.get(1)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "Value Date", "02/07/2026",
                "Reference Number", "REF00002",
                "Transaction Type", "Debit",
                "Particulars", "Grocery Store",
                "Debit", "1,200.00",
                "Balance", "13,800.00"));
        assertThat(rows).allSatisfy(row -> assertThat(CsvParser.parseDate(row.get("Value Date")))
                .as("each transaction keeps a parseable date, so none is dropped downstream")
                .isNotNull());
    }

    /**
     * The safety gate that keeps this capability from re-admitting the exact cross-contamination
     * it was reverted for once already (see {@code PdfTableLocator.mergeHeaderLines}'s own doc
     * comment): an interior tier is only ever admitted alongside an outer group that is ITSELF
     * later corroborated -- every seed-line cell that never gets joined by a later line must be
     * individually recognized vocabulary on its own, or the whole merge is refused. Reproduces a
     * real Axis Bank credit-card statement's own regression: an unrelated section caption
     * ("Account Summary") sitting one line above a real, fully-recognized header line became a
     * seed on its own merits and was never joined by anything below it, so admitting the header
     * line's own four recognized cells as new columns would otherwise also admit the caption as a
     * fifth, spurious one.
     */
    @Test
    void aCaptionAboveAFullyRecognizedHeaderLineIsNotAdmittedAsAColumn() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                // The caption: no date, no number, sits within HEADER_WRAP_MAX_GAP of the real
                // header below it -- exactly what an ordinary wrapped-header seed looks like, and
                // recognized as nothing.
                run("Account Summary", 50f, 100f, 90f),

                // The real header, entirely recognized vocabulary on its own.
                run("Date", 50f, 108f, 20f), run("Transaction Details", 150f, 108f, 90f),
                run("Merchant Category", 300f, 108f, 90f), run("Amount", 430f, 108f, 34f),

                run("01/07/2026", 50f, 130f, 45f), run("Sample Merchant", 150f, 130f, 65f),
                run("Retail", 300f, 130f, 30f), run("500.00", 430f, 130f, 30f)));

        DocumentContext ctx = new DocumentContext("PDF", "InteriorTierWrappedHeaderTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections().get(0).rows().get(0).keySet())
                .as("the caption never becomes a fifth column")
                .containsExactlyInAnyOrder("Date", "Transaction Details", "Merchant Category", "Amount");
    }

    /**
     * The corpus-wide safety property: this capability may make a table appear that was not being
     * located, and it may not disturb one that was. Two existing WRAPPED_HEADER fixtures already
     * cover the ordinary (non-interior) wrap shapes -- this asserts THEY are untouched by a
     * capability that generalizes the same merge.
     */
    @Test
    void anOrdinaryWrappedHeaderTableIsStillLocatedTheSameWay() throws Exception {
        byte[] bytes = com.finora.imports.pdf.fixtures.PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample();
        List<PositionedText> runs = new PdfTextExtractor().extract(bytes);

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, null);

        assertThat(doc.sections().get(0).rows()).hasSize(3);
        assertThat(doc.sections().get(0).rows().get(0).keySet()).containsExactly(
                "FD Number", "Currency Code", "Deposit Principal", "Open/Value Date",
                "Rate Of Interest", "Maturity Amount", "Nomination Registered");
    }
}
