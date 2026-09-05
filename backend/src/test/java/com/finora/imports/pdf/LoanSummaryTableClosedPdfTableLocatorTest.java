package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two real HSBC credit-card statements (HSBC CC.pdf, HSBC CC new.pdf) each print a "Loan Summary
 * Table" caption near the end of their real transaction table, introducing an unrelated
 * loan-on-card EMI schedule -- confirmed via those documents' own ground truth: no second account
 * exists in either. Without {@link PdfTableLocator#LOAN_SUMMARY_TABLE_MARKER}, that schedule's own
 * header ("Loan Booking Date" and "Installment amount" cells each individually satisfying
 * HEADER_HINTS's per-word match) reads as a genuinely new table and opens its own phantom section
 * -- one that can never stage a real row (its date-shaped column name never equals any whole
 * {@code DATE_HINTS} entry), but that still steals every real credit-card-summary phrase printed
 * after it, which is why the real ledger section was separately observed mistyped SAVINGS on a
 * credit-card statement. This test proves both symptoms resolve together.
 *
 * <p>Coordinates and column shapes only, per the Synthetic Fixture Policy -- every value below is
 * invented.
 */
class LoanSummaryTableClosedPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void loanSummaryTableCaption_staysOneSection_notAPhantomSecond() {
        List<PositionedText> runs = new ArrayList<>();
        // The real ledger's own header and one transaction row.
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Description", 90f, 180f, 100f));
        runs.add(run("Debit", 300f, 340f, 100f));
        runs.add(run("Credit", 400f, 440f, 100f));
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("SAMPLE MERCHANT PAYMENT", 90f, 220f, 120f));
        runs.add(run("500.00", 400f, 440f, 120f));
        // Real document's own credit-card-summary vocabulary -- must survive attached to the real
        // ledger section, not be stolen by a phantom second section, for suggestedAccountTypeFor
        // to type this section CREDIT_CARD rather than fall through to its SAVINGS default.
        runs.add(run("Minimum Payment Due 500.00", 30f, 200f, 140f));
        runs.add(run("Available Credit Limit 30,000.00", 30f, 220f, 160f));
        // The caption that, on the real documents, precedes an unrelated loan-schedule table.
        runs.add(run("Loan Summary Table", 30f, 150f, 180f));
        // The loan schedule's own header -- shaped exactly like the real one: a date-hint word
        // inside "Loan Booking Date" and an amount-hint word inside "Installment amount" clear
        // looksLikeHeaderRow's match-count and density gates on their own.
        runs.add(run("Merchant Name", 30f, 100f, 200f));
        runs.add(run("Loan Booking Date", 110f, 190f, 200f));
        runs.add(run("Principal", 200f, 260f, 200f));
        runs.add(run("Interest", 270f, 320f, 200f));
        runs.add(run("Installment amount", 330f, 420f, 200f));
        runs.add(run("Tenure", 430f, 470f, 200f));
        runs.add(run("SAMPLE LOAN VENDOR", 30f, 100f, 220f));
        runs.add(run("01 FEB 2026", 110f, 190f, 220f));
        runs.add(run("1,000.00", 200f, 260f, 220f));
        runs.add(run("25.00", 270f, 320f, 220f));
        runs.add(run("100.00", 330f, 420f, 220f));
        runs.add(run("3/3", 430f, 470f, 220f));

        DocumentContext ctx = new DocumentContext("PDF", "LoanSummaryTableClosedPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("LOAN_SUMMARY_TABLE_CLOSED")
                .doesNotContain("COMPOSITE_STATEMENT");

        PdfTableLocator.LocatedSection section = doc.sections().get(0);
        assertThat(section.rows()).hasSize(1);
        assertThat(section.rows().get(0)).containsEntry("Credit", "500.00");
        assertThat(section.rows().get(0).get("Description"))
                .as("the real ledger section must keep its own credit-card-summary vocabulary, not "
                        + "have it stolen by a phantom second section")
                .contains("Minimum Payment Due").contains("Available Credit Limit");
    }
}
