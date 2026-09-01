package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED: the escape hatch
 * {@code HEADERLESS_MIN_TRANSACTION_ROWS}'s own row-count floor needed to recover a real HSBC
 * credit-card statement's genuine transaction -- one real transaction, one short of the floor,
 * corroborated by the document's own printed OPENING BALANCE / NET OUTSTANDING BALANCE
 * reconciling exactly against it.
 *
 * <p>Coordinates copied verbatim from a direct PositionedText inspection of the real HSBC CC.pdf:
 * "OPENING BALANCE" at y=289.5, x=77.3-144.2, its value "1,582.00" on the same row at
 * x=381.1-408.4; the transaction itself at y=297.7/299.6 ("30JUN", "BBPS PMT ...", "1,582.00",
 * "CR"); "NET OUTSTANDING BALANCE" at y=375.0, x=78.7-180.2, its value "0.00" on the same row at
 * x=393.1-406.7. No account-identifying values (the reference code) are needed to prove the
 * mechanism, so this test uses a generic merchant description instead of the real one, per the
 * Synthetic Fixture Policy already established in {@link HeaderlessLayoutInferenceTest} for
 * fixtures motivated by a real document but not requiring its exact text.
 */
class HeaderlessBalanceReconciliationTest {

    private static PositionedText run(String text, float x, float endX, float y) {
        return new PositionedText(text, x, y, 0, endX - x);
    }

    @Test
    void extract_readsTheRealHsbcOpeningAndClosingBalance_andReconcilesTheOneRealTransaction() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("OPENING BALANCE", 77.3f, 144.2f, 289.5f),
                run("1,582.00", 381.1f, 408.4f, 289.5f),
                run("30JUN", 30.7f, 52.1f, 297.7f),
                run("BBPS PMT REFERENCE", 77.3f, 213.1f, 299.6f),
                run("1,582.00", 381.1f, 408.4f, 299.6f),
                run("CR", 413.5f, 423.6f, 299.6f),
                run("23JUL", 31.4f, 51.7f, 375.0f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 375.0f),
                run("0.00", 393.1f, 406.7f, 375.0f)));

        PdfTableLocator locator = new PdfTableLocator();
        List<List<PositionedText>> grouped = StatementSummaryExtractor.groupIntoRows(runs);

        List<List<PositionedText>> candidates = new ArrayList<>();
        for (List<PositionedText> row : grouped) {
            if (row.stream().anyMatch(t -> t.text().trim().equals("30JUN"))) candidates.add(row);
        }

        assertThat(locator.corroboratedByPrintedBalanceReconciliationForTest(candidates, grouped)).isTrue();
    }

    @Test
    void extract_declinesToCorroborate_whenTheArithmeticDoesNotReconcile() {
        // Same shape, but the closing balance is wrong by 1 rupee -- must not corroborate on a
        // near miss.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("OPENING BALANCE", 77.3f, 144.2f, 289.5f),
                run("1,582.00", 381.1f, 408.4f, 289.5f),
                run("30JUN", 30.7f, 52.1f, 297.7f),
                run("BBPS PMT REFERENCE", 77.3f, 213.1f, 299.6f),
                run("1,582.00", 381.1f, 408.4f, 299.6f),
                run("CR", 413.5f, 423.6f, 299.6f),
                run("23JUL", 31.4f, 51.7f, 375.0f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 375.0f),
                run("1.00", 393.1f, 406.7f, 375.0f)));

        PdfTableLocator locator = new PdfTableLocator();
        List<List<PositionedText>> grouped = StatementSummaryExtractor.groupIntoRows(runs);

        List<List<PositionedText>> candidates = new ArrayList<>();
        for (List<PositionedText> row : grouped) {
            if (row.stream().anyMatch(t -> t.text().trim().equals("30JUN"))) candidates.add(row);
        }

        assertThat(locator.corroboratedByPrintedBalanceReconciliationForTest(candidates, grouped)).isFalse();
    }

    /** End-to-end proof through the real public entry point, not just the reconciliation helper
     *  directly: a document whose only header is a later, unrelated table, and whose pre-header
     *  region has exactly one real transaction below the floor, corroborated by its own printed
     *  balance summary -- staged correctly, with the surrounding text attached as this section's
     *  own auxiliary text (see {@code tryCorroboratedFallback}'s own doc comment for why this
     *  matters: without it, the recovered section carries no account identity at all). */
    @Test
    void locateAll_recoversTheOneRealTransaction_beforeALaterUnrelatedHeader() {
        List<PositionedText> positioned = new ArrayList<>(List.of(
                // A full-year date somewhere on the page (matching the real document's own
                // statement-generation-date line) -- required for "30JUN" below to resolve via
                // resolveYearlessDate; yearsByPage has nothing to work with otherwise.
                run("10 AUG 2026", 370.3f, 412.7f, 51.9f),
                run("MR SOME CARDHOLDER", 58.1f, 162.1f, 72.6f),
                run("OPENING BALANCE", 77.3f, 144.2f, 289.5f),
                run("1,582.00", 381.1f, 408.4f, 289.5f),
                run("30JUN", 30.7f, 52.1f, 297.7f),
                run("BBPS PMT REFERENCE", 77.3f, 213.1f, 299.6f),
                run("1,582.00", 381.1f, 408.4f, 299.6f),
                run("CR", 413.5f, 423.6f, 299.6f),
                run("23JUL", 31.4f, 51.7f, 375.0f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 375.0f),
                run("0.00", 393.1f, 406.7f, 375.0f)));
        // A later, unrelated real table on page 1 -- its own genuine header (needs both a date hint
        // and an amount hint to clear looksLikeHeaderRow's own threshold, same as
        // HeaderlessLayoutBeforeLaterHeaderTest's own fixture).
        positioned.add(new PositionedText("Loan Booking Date", 30f, 56f, 1, 60f));
        positioned.add(new PositionedText("Installment amount", 350f, 56f, 1, 70f));
        positioned.add(new PositionedText("01 Mar 2026", 30f, 76f, 1, 55f));
        positioned.add(new PositionedText("350.00", 350f, 76f, 1, 40f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        List<Map<String, String>> recovered = doc.sections().get(0).rows();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.get(0)).containsEntry("Amount", "1,582.00 CR");
        assertThat(doc.sections().get(0).auxiliaryText())
                .anyMatch(line -> line.contains("MR SOME CARDHOLDER"));
        List<String> capabilities = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(capabilities).contains(
                "HEADERLESS_LAYOUT_BEFORE_LATER_HEADER", "HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED");
    }
}
