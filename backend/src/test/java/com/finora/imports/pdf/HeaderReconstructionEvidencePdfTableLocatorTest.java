package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Header-reconstruction evidence: {@link PdfTableLocator.HeaderReconstructionFinding}, fired only
 * when a multi-line header candidate carrying real transaction-ledger vocabulary (see {@code
 * AMOUNT_COLUMN_HINTS}) fails to merge AND the header the section eventually settles on is
 * suspiciously small (see {@code LOW_CONFIDENCE_TRANSACTION_HEADER_COLUMN_COUNT}'s own doc
 * comment). Built from two real, independently-confirmed documents -- an Indian Overseas Bank
 * savings statement and an SBI credit-card statement's supplementary-cardholder section -- but
 * every fixture below is fully hand-synthesized per the Synthetic Fixture Policy; no value from
 * either real document appears here, only the structural SHAPE that caused each failure (a
 * two-line label-only header block whose lower line's columns don't fall within the real
 * production {@code HEADER_WRAP_MAX_COLUMN_JOIN} tolerance of the upper line's own columns).
 */
class HeaderReconstructionEvidencePdfTableLocatorTest {

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

    /**
     * The IOB shape: a two-line label-only header block whose lower line carries real
     * transaction-ledger vocabulary (particulars/debit/credit/balance) but whose columns don't
     * align with the upper line's own -- exactly the real document's own "Date(Value Ref No. /
     * Transaction" seeding only 2 columns while "Particulars / Debit(Rs) / Credit(Rs) /
     * Balance(Rs)" needed 4. The merge fails, the loop falls through to a later, narrower
     * fragment ("Date) / Type") that DOES score alone, and real transaction-shaped data rows
     * bucket against that 2-column fallback instead of the real header.
     */
    private static List<PositionedText> upperHeaderLine(float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run("Ref No.", 70f, 40f, y));
        row.add(run("Narration", 300f, 60f, y));
        return row;
    }

    private static List<PositionedText> vocabularyBearingLowerLine(float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run("Particulars", 90f, 60f, y));
        row.add(run("Debit", 200f, 30f, y));
        row.add(run("Credit", 280f, 30f, y));
        row.add(run("Balance", 360f, 40f, y));
        return row;
    }

    /** Scores alone (date + type, both HEADER_HINTS/DATE_HINTS matches) -- the narrower fallback
     *  fragment that wins once the real 4-column line above it fails to merge into anything. Cell
     *  x=20 is deliberately far from every column {@link #vocabularyBearingLowerLine} seeds
     *  (90/200/280/360), so a merge attempt starting there also fails, matching the real
     *  document's own "Date)" cell never joining the tier above it either. */
    private static List<PositionedText> narrowFallbackHeaderLine(float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run("Date)", 20f, 30f, y));
        row.add(run("Type", 450f, 30f, y));
        return row;
    }

    private static List<PositionedText> narrowFallbackDataRow(String date, String type, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 20f, 50f, y));
        row.add(run(type, 450f, 40f, y));
        return row;
    }

    @Test
    void aWrappedHeaderThatFailsToMerge_isRecordedAsHeaderReconstructionFinding() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(upperHeaderLine(100f));
        positioned.addAll(vocabularyBearingLowerLine(104f));
        positioned.addAll(narrowFallbackHeaderLine(108f));
        positioned.addAll(narrowFallbackDataRow("15.03.2026", "Payment", 112f));
        positioned.addAll(narrowFallbackDataRow("16.03.2026", "Transfer", 116f));

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        var findings = doc.sections().get(0).evidence().headerReconstructionFindings();
        assertThat(findings).hasSize(1);
        var finding = findings.get(0);
        assertThat(finding.reason()).isEqualTo("TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN");
        assertThat(finding.sectionIndex()).isEqualTo(0);
        assertThat(finding.vocabularySignals()).contains("debit", "credit", "balance");
        assertThat(finding.acceptedHeaderColumnCount()).isEqualTo(2);
    }

    /**
     * The SBI shape: a document whose FIRST section reconstructs correctly (a normal ledger
     * header, real rows) and whose SECOND section -- introduced by an account banner, standing in
     * for the real document's own "TRANSACTIONS FOR <supplementary cardholder>" marker -- hits
     * the exact same wrapped-header failure as the test above. Proves the finding is scoped to
     * the RIGHT section: section 0 must carry none of it, only section 1.
     */
    @Test
    void theFindingIsScopedToTheSectionThatActuallyFailed_notTheWholeDocument() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        positioned.addAll(ledgerRow("02.01.2026", "Grocery Store", "200.00", "9750.00", 150f));
        positioned.add(line("SAVINGS ACCOUNT - 222222222222", 170f));
        positioned.addAll(upperHeaderLine(190f));
        positioned.addAll(vocabularyBearingLowerLine(194f));
        positioned.addAll(narrowFallbackHeaderLine(198f));
        positioned.addAll(narrowFallbackDataRow("15.03.2026", "Payment", 202f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).evidence().headerReconstructionFindings()).isEmpty();
        var findings = doc.sections().get(1).evidence().headerReconstructionFindings();
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).sectionIndex()).isEqualTo(1);
        assertThat(findings.get(0).vocabularySignals()).contains("debit", "credit", "balance");
        assertThat(findings.get(0).acceptedHeaderColumnCount()).isEqualTo(2);
    }

    @Test
    void aCleanStatement_neverGeneratesHeaderReconstructionFinding() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        positioned.addAll(ledgerRow("02.01.2026", "Grocery Store", "200.00", "9750.00", 150f));

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).evidence().headerReconstructionFindings()).isEmpty();
    }

    /**
     * The HSBC CC / Kotak CC lesson: vocabulary-bearing failed merges alone are not enough --
     * confirmed against the real corpus, where dense two-column tariff/legal pages produced many
     * such near-misses on documents that parsed completely correctly. Two vocabulary-bearing rows
     * are recorded here (same shape as the positive tests above) but the document's REAL header,
     * scanned afterward, is a normal 5-column ledger -- the consequence gate must suppress the
     * finding even though vocabulary evidence exists.
     */
    @Test
    void vocabularyNoiseWithoutAWeakFallbackHeader_neverGeneratesFinding() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(upperHeaderLine(80f));
        positioned.addAll(vocabularyBearingLowerLine(84f));
        // No narrow fallback follows -- instead, the document's REAL header, a normal 5-column
        // ledger, is scanned next and wins outright.
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Coffee Shop", "50.00", "9950.00", 130f));
        positioned.addAll(ledgerRow("02.01.2026", "Grocery Store", "200.00", "9750.00", 150f));

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(0).evidence().headerReconstructionFindings()).isEmpty();
    }

    /**
     * The remaining loophole named before commit: {@code acceptedHeaderColumnCount <= 3} alone is
     * not proof of damage -- a genuinely narrow, intentional 2-column table (a small interest or
     * fee summary, not a transaction ledger at all) must not warn just because it happens to be
     * narrow. Deliberately different from every finding-positive test above: "Date | Amount"
     * scores as a header entirely on its own, on the very first line, with no multi-line merge
     * ever attempted -- there is no failed reconstruction here for the vocabulary gate to catch,
     * because none was needed. This is the case {@code affectedUnparseableRows} was deliberately
     * NOT added to close (see HeaderReconstructionFinding's own doc comment): this evidence layer
     * only ever claims a RECONSTRUCTION was uncertain, never that a narrow header is inherently
     * wrong.
     */
    @Test
    void aGenuinelyNarrowIntentionalHeader_neverGeneratesFinding() {
        List<PositionedText> positioned = new ArrayList<>();
        List<PositionedText> narrowRealHeader = new ArrayList<>();
        narrowRealHeader.add(run("Date", 70f, 30f, 100f));
        narrowRealHeader.add(run("Amount", 300f, 50f, 100f));
        positioned.addAll(narrowRealHeader);
        List<PositionedText> row1 = new ArrayList<>();
        row1.add(run("01.01.2026", 71f, 42f, 120f));
        row1.add(run("299.00", 301f, 40f, 120f));
        positioned.addAll(row1);
        List<PositionedText> row2 = new ArrayList<>();
        row2.add(run("01.02.2026", 71f, 42f, 140f));
        row2.add(run("299.00", 301f, 40f, 140f));
        positioned.addAll(row2);

        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, null);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(0).evidence().headerReconstructionFindings()).isEmpty();
    }
}
