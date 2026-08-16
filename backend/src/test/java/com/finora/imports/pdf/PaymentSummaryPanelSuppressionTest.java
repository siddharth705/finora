package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PAYMENT_SUMMARY_PANEL_SUPPRESSED: a credit card's own payment-summary panel (Total/Minimum
 * Payment Due, Payment Due Date, Statement Generation Date, Credit Limit, ...) satisfies {@link
 * PdfTableLocator#looksLikeHeaderRow}'s date-plus-vocabulary check exactly like a real transaction
 * table -- found on two real credit-card statements with otherwise unrelated layouts (a real Axis
 * statement with an explicit "PAYMENT SUMMARY" banner, a real HDFC statement with no such banner
 * at all). See {@code PdfTableLocator.looksLikePaymentSummaryPanel} for the three-signal gate this
 * exercises directly, and its own doc comment for the real regression an earlier, two-signal
 * version of this gate caused (broke real recurring/fixed-deposit installment schedules, which are
 * also short and also lack a narration column).
 *
 * <p>Every fixture below is fully hand-synthesized -- invented column geometry, dates, and
 * amounts -- per the Synthetic Fixture Policy; no value from either real document appears here.
 */
class PaymentSummaryPanelSuppressionTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static List<PositionedText> realHeader(float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run("Date", 70f, 30f, y));
        row.add(run("Description", 200f, 70f, y));
        row.add(run("Amount", 400f, 50f, y));
        return row;
    }

    private static List<PositionedText> realRow(String date, String desc, String amount, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 71f, 42f, y));
        row.add(run(desc, 201f, desc.length() * 5.2f, y));
        row.add(run(amount, 401f, 30f, y));
        return row;
    }

    @Test
    void genuinePaymentSummaryPanel_isSuppressed_andItsTextSurvivesInTheNextSectionsAuxiliaryText() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Total Payment Due", 70f, 90f, 100f));
        positioned.add(run("Payment Due Date", 250f, 90f, 100f));
        positioned.add(run("Statement Generation Date", 400f, 110f, 100f));
        positioned.add(run("Credit Limit", 550f, 60f, 100f));
        positioned.add(run("27,665.16", 70f, 60f, 115f));
        positioned.add(run("11/08/2026", 250f, 60f, 115f));
        positioned.add(run("22/07/2026", 400f, 60f, 115f));
        positioned.add(run("2,19,000.00", 550f, 70f, 115f));
        positioned.addAll(realHeader(135f));
        positioned.addAll(realRow("01.02.2026", "Merchant One", "500.00", 155f));
        positioned.addAll(realRow("02.02.2026", "Merchant Two", "600.00", 175f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("the panel does not become its own section").hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("Date", "01.02.2026").containsEntry("Amount", "500.00");
        assertThat(rows.get(1)).containsEntry("Date", "02.02.2026").containsEntry("Amount", "600.00");

        // "Never lose information" -- the demoted panel's own row survives as text on the section
        // that absorbs it, exactly what PdfTableLocator.closeCurrentSection's own doc comment
        // promises: a fresh pendingAuxiliary list is used up to this point, and the panel's demoted
        // content is what it accumulates BEFORE the real section closes and inherits it.
        assertThat(doc.sections().get(0).auxiliaryText())
                .as("the panel's own row, demoted to text rather than silently dropped")
                .anySatisfy(line -> assertThat(line).contains("27,665.16"));

        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
    }

    @Test
    void aSuppressedPanelAsTheDocumentsFinalSection_stillKeepsItsTextRatherThanLosingIt() {
        // Regression coverage for a bug found in self-review, never reached a real document: the
        // end-of-document flush call site originally ignored closeCurrentSection's return value,
        // so a payment-summary panel sitting AFTER the real ledger (nothing else in this fixture)
        // would have its demoted text silently discarded -- the exact "never lose information"
        // failure e65af76 fixed elsewhere in this same class, reappearing in a new mechanism.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.addAll(realHeader(100f));
        positioned.addAll(realRow("01.02.2026", "Merchant One", "500.00", 120f));
        positioned.addAll(realRow("02.02.2026", "Merchant Two", "600.00", 140f));
        positioned.add(run("Total Payment Due", 70f, 90f, 160f));
        positioned.add(run("Payment Due Date", 250f, 90f, 160f));
        positioned.add(run("Statement Generation Date", 400f, 110f, 160f));
        positioned.add(run("Credit Limit", 550f, 60f, 160f));
        positioned.add(run("27,665.16", 70f, 60f, 175f));
        positioned.add(run("11/08/2026", 250f, 60f, 175f));
        positioned.add(run("22/07/2026", 400f, 60f, 175f));
        positioned.add(run("2,19,000.00", 550f, 70f, 175f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("real ledger, plus a trailing auxiliary-only section for the "
                + "suppressed panel's demoted text -- not silently dropped").hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(2);
        assertThat(doc.sections().get(1).rows()).as("the trailing section carries no rows of its own").isEmpty();
        assertThat(doc.sections().get(1).auxiliaryText())
                .as("the panel's own row, preserved rather than lost at end of document")
                .anySatisfy(line -> assertThat(line).contains("27,665.16"));
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
    }

    @Test
    void aRowCountAboveTheCeiling_isNotSuppressed_evenWithAFullPhraseMatch() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Total Payment Due", 70f, 90f, 100f));
        positioned.add(run("Payment Due Date", 250f, 90f, 100f));
        positioned.add(run("Statement Generation Date", 400f, 110f, 100f));
        positioned.add(run("Credit Limit", 550f, 60f, 100f));
        // Three data rows -- one past PAYMENT_SUMMARY_PANEL_MAX_ROWS (2) -- under a header that
        // would otherwise satisfy the phrase-vocabulary gate comfortably.
        positioned.add(run("27,665.16", 70f, 60f, 115f));
        positioned.add(run("11/08/2026", 250f, 60f, 115f));
        positioned.add(run("22/07/2026", 400f, 60f, 115f));
        positioned.add(run("2,19,000.00", 550f, 70f, 115f));
        positioned.add(run("10,081.99", 70f, 60f, 125f));
        positioned.add(run("12/08/2026", 250f, 60f, 125f));
        positioned.add(run("23/07/2026", 400f, 60f, 125f));
        positioned.add(run("2,20,000.00", 550f, 70f, 125f));
        positioned.add(run("9,000.00", 70f, 60f, 135f));
        positioned.add(run("13/08/2026", 250f, 60f, 135f));
        positioned.add(run("24/07/2026", 400f, 60f, 135f));
        positioned.add(run("2,21,000.00", 550f, 70f, 135f));
        positioned.addAll(realHeader(155f));
        positioned.addAll(realRow("01.02.2026", "Merchant One", "500.00", 175f));
        positioned.addAll(realRow("02.02.2026", "Merchant Two", "600.00", 195f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("both sections survive -- the three-row block is left alone").hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(3);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
    }

    @Test
    void aRecognizedDescriptionColumn_isNotSuppressed_evenWithAFullPhraseMatch() {
        // The compound safety net: a real narration column present anywhere on the header refuses
        // suppression outright, regardless of how strongly the rest of the header reads as a
        // payment summary -- this is what keeps a genuine short ledger with unusual column naming
        // (this codebase's real recurring/fixed-deposit case) from ever being demoted.
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Total Payment Due", 70f, 90f, 100f));
        positioned.add(run("Payment Due Date", 250f, 90f, 100f));
        positioned.add(run("Statement Generation Date", 400f, 110f, 100f));
        positioned.add(run("Remarks", 560f, 60f, 100f));
        positioned.add(run("27,665.16", 70f, 60f, 115f));
        positioned.add(run("11/08/2026", 250f, 60f, 115f));
        positioned.add(run("22/07/2026", 400f, 60f, 115f));
        positioned.add(run("as of today", 560f, 60f, 115f));
        positioned.addAll(realHeader(135f));
        positioned.addAll(realRow("01.02.2026", "Merchant One", "500.00", 155f));
        positioned.addAll(realRow("02.02.2026", "Merchant Two", "600.00", 175f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
    }

    @Test
    void fewerThanTwoPhraseMatches_isNotSuppressed_evenWithinTheRowCeiling() {
        List<PositionedText> positioned = new ArrayList<>();
        // "Statement Date" alone matches no PAYMENT_SUMMARY_FIELD_PHRASES entry (it is not
        // "statement generation date" or "statement period"); "Credit Limit" alone is only one
        // match -- below MIN_PAYMENT_SUMMARY_FIELD_MATCHES (2).
        positioned.add(run("Statement Date", 70f, 80f, 100f));
        positioned.add(run("Credit Limit", 400f, 70f, 100f));
        positioned.add(run("22/07/2026", 70f, 60f, 115f));
        positioned.add(run("2,19,000.00", 400f, 70f, 115f));
        positioned.addAll(realHeader(135f));
        positioned.addAll(realRow("01.02.2026", "Merchant One", "500.00", 155f));
        positioned.addAll(realRow("02.02.2026", "Merchant Two", "600.00", 175f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("PAYMENT_SUMMARY_PANEL_SUPPRESSED");
    }
}
