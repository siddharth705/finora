package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code closeCurrentSection} attaches ALL of {@code pendingAuxiliary} to the section that is
 * CLOSING -- correct for most of it, but wrong for its own trailing run when that run is really
 * the NEXT section's own leading identity block, printed just before that section's own header is
 * recognized. Confirmed on a real HDFC composite statement: a Recurring Deposit's own "Account
 * Number"/"Account Type: RECURRING DEPOSIT"/"RD ACCOUNT SUMMARY" identity block prints immediately
 * after an unrelated Fixed Deposit ledger's own last row, landing in the FD section's own trailing
 * auxiliary text -- PdfMetadataExtractor then correctly extracts AN account number from it, just
 * the wrong section's, and the FD section's real structural evidence gets diluted by RD-shaped
 * text that was never really its own.
 *
 * <p>Coordinates and shapes only, per the Synthetic Fixture Policy -- every value below is
 * invented.
 */
class TrailingIdentityCarriedForwardPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void trailingIdentityBlock_carriesForwardToTheSectionItActuallyIntroduces() {
        List<PositionedText> runs = new ArrayList<>();
        // First section: an unrelated table, one real row.
        runs.add(run("Date", 30f, 64f, 100f));
        runs.add(run("Amount", 400f, 440f, 100f));
        runs.add(run("01 Jun 2026", 30f, 90f, 120f));
        runs.add(run("500.00", 400f, 440f, 120f));

        // Trailing text collected before the next header is recognized -- the NEXT section's own
        // identity line.
        runs.add(run("Account Number : 5551234567890", 30f, 250f, 140f)); // synthetic-ok: invented placeholder digits, not a real account number

        // The second section's own genuinely different header (two HEADER_HINTS matches --
        // "Due Date" and "Amount Paid" -- clearing looksLikeHeaderRow's match-count bar) and one
        // row.
        runs.add(run("Serial", 30f, 70f, 200f));
        runs.add(run("Due Date", 90f, 150f, 200f));
        runs.add(run("Amount Paid", 400f, 440f, 200f));
        runs.add(run("1", 30f, 40f, 220f));
        runs.add(run("02 Jun 2026", 90f, 150f, 220f));
        runs.add(run("300.00", 400f, 440f, 220f));

        DocumentContext ctx = new DocumentContext("PDF", "TrailingIdentityCarriedForwardPdfTableLocatorTest");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(ctx.capabilities()).extracting(c -> c.capability())
                .contains("TRAILING_IDENTITY_CARRIED_FORWARD");
        assertThat(doc.sections()).hasSize(2);

        PdfTableLocator.LocatedSection first = doc.sections().get(0);
        assertThat(first.auxiliaryText().stream().noneMatch(l -> l.contains("Account Number")))
                .as("the identity block must not stay on the section it doesn't belong to")
                .isTrue();

        PdfTableLocator.LocatedSection second = doc.sections().get(1);
        assertThat(second.auxiliaryText())
                .as("the identity line must carry forward to the section it actually introduces")
                .contains("Account Number : 5551234567890"); // synthetic-ok: invented placeholder digits, not a real account number
    }
}
