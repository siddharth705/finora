package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>A second real HSBC document (10 real transactions across the same statement shape, mostly
 * unmarked debits, plus coincidental date+amount noise before its own "OPENING BALANCE" label)
 * later proved two more things this class's tests cover further down: an unmarked row must
 * default to DR rather than being dropped, and the candidate pool must be positionally bracketed
 * between "OPENING BALANCE" and "NET OUTSTANDING BALANCE" so that default can't readmit the same
 * noise the original CR/DR-marker requirement used to filter for free. Coordinates for that
 * second shape are hand-synthesized (not copied from the second document), per the same policy.
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

    /** A second real HSBC document (10 real transactions, mostly unmarked) proved the CR/DR-marker
     *  requirement above was too strict: it silently dropped every genuine debit row that carries
     *  no marker at all, which real evidence shows is most of them -- only credits are marked.
     *  {@code signedTransactionAmount} now defaults an unmarked row to DR (a purchase). This test
     *  proves that default reconciles correctly on its own, independent of the positional
     *  bracketing {@link #locateAll_ignoresNoiseOutsideTheBalanceBracket_whileRecoveringUnmarkedRows}
     *  below covers. */
    @Test
    void extract_defaultsAnUnmarkedRowToDebit_andStillReconciles() {
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("OPENING BALANCE", 77.3f, 144.2f, 100f),
                run("5,000.00", 381.1f, 408.4f, 100f),
                run("24DEC", 30.7f, 52.1f, 110f),
                run("BBPS PMT REFERENCE", 77.3f, 213.1f, 110f),
                run("1,000.00", 381.1f, 408.4f, 110f),
                run("CR", 413.5f, 423.6f, 110f),
                run("01JAN", 30.7f, 52.1f, 120f),
                run("SOME MERCHANT A", 77.3f, 213.1f, 120f),
                run("600.00", 381.1f, 408.4f, 120f),
                run("06JAN", 30.7f, 52.1f, 130f),
                run("SOME MERCHANT B", 77.3f, 213.1f, 130f),
                run("400.00", 381.1f, 408.4f, 130f),
                run("23JAN", 31.4f, 51.7f, 140f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 140f),
                run("5,000.00", 393.1f, 406.7f, 140f)));

        PdfTableLocator locator = new PdfTableLocator();
        List<List<PositionedText>> grouped = StatementSummaryExtractor.groupIntoRows(runs);

        List<List<PositionedText>> candidates = new ArrayList<>();
        for (List<PositionedText> row : grouped) {
            if (row.stream().anyMatch(t -> Set.of("24DEC", "01JAN", "06JAN").contains(t.text().trim()))) {
                candidates.add(row);
            }
        }

        assertThat(locator.corroboratedByPrintedBalanceReconciliationForTest(candidates, grouped)).isTrue();
    }

    /** End-to-end proof of the fix for the second real HSBC document's shape: a headerless ledger
     *  with mostly-unmarked debit rows, preceded by coincidental date+amount noise (the statement
     *  generation date paired with its own total-amount-due figure) that sits BEFORE the
     *  document's own "OPENING BALANCE" label. Real evidence: exactly this noise shape (plus a
     *  second, analogous billing-period noise line the real document keeps as one combined,
     *  non-date-parseable text run -- not reproduced here, see the Synthetic Fixture Policy)
     *  defeated the ordinary clustering path on both real HSBC documents. {@code
     *  tryCorroboratedFallback} now restricts candidates to rows strictly between "OPENING
     *  BALANCE" and "NET OUTSTANDING BALANCE" -- if that bracketing were missing, this noise row
     *  would also default to DR and the arithmetic below would not reconcile (its amount doesn't
     *  cancel), so this test fails closed on a regression rather than silently recovering a wrong
     *  section. */
    @Test
    void locateAll_ignoresNoiseOutsideTheBalanceBracket_whileRecoveringUnmarkedRows() {
        List<PositionedText> positioned = new ArrayList<>(List.of(
                // Noise, BEFORE "OPENING BALANCE": a statement-generation date paired with a
                // total-amount-due figure -- also supplies the full year "2026" that "24DEC" /
                // "01JAN" / "06JAN" / "23JAN" below need to resolve via resolveYearlessDate.
                run("10 FEB 2026", 370.3f, 412.7f, 10f),
                run("4,999.99", 440f, 470f, 10f),
                run("MR SOME CARDHOLDER", 58.1f, 162.1f, 30f),
                run("OPENING BALANCE", 77.3f, 144.2f, 100f),
                run("5,000.00", 381.1f, 408.4f, 100f),
                run("24DEC", 30.7f, 52.1f, 110f),
                run("BBPS PMT REFERENCE", 77.3f, 213.1f, 110f),
                run("1,000.00", 381.1f, 408.4f, 110f),
                run("CR", 413.5f, 423.6f, 110f),
                run("01JAN", 30.7f, 52.1f, 120f),
                run("SOME MERCHANT A", 77.3f, 213.1f, 120f),
                run("600.00", 381.1f, 408.4f, 120f),
                run("06JAN", 30.7f, 52.1f, 130f),
                run("SOME MERCHANT B", 77.3f, 213.1f, 130f),
                run("400.00", 381.1f, 408.4f, 130f),
                run("23JAN", 31.4f, 51.7f, 140f),
                run("NET OUTSTANDING BALANCE", 78.7f, 180.2f, 140f),
                run("5,000.00", 393.1f, 406.7f, 140f)));
        // A later, unrelated real table on page 1 -- same shape as the other end-to-end test above.
        positioned.add(new PositionedText("Loan Booking Date", 30f, 56f, 1, 60f));
        positioned.add(new PositionedText("Installment amount", 350f, 56f, 1, 70f));
        positioned.add(new PositionedText("01 Mar 2026", 30f, 76f, 1, 55f));
        positioned.add(new PositionedText("350.00", 350f, 76f, 1, 40f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        List<Map<String, String>> recovered = doc.sections().get(0).rows();
        assertThat(recovered).hasSize(3);
        assertThat(recovered).extracting(row -> row.get("Amount"))
                .containsExactlyInAnyOrder("1,000.00 CR", "600.00 DR", "400.00 DR");
        List<String> capabilities = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(capabilities).contains(
                "HEADERLESS_LAYOUT_BEFORE_LATER_HEADER", "HEADERLESS_BALANCE_RECONCILIATION_CORROBORATED");
    }
}
