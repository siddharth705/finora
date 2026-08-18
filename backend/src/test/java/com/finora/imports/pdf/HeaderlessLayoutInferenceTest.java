package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INFERRED_HEADERLESS_LAYOUT: a transaction table with no header row anywhere in the document.
 *
 * <p>Found on a real SBI savings statement whose column vocabulary (Date/Narration/Debit/
 * Credit/Balance) never appears as text at all -- {@code looksLikeHeaderRow} never scores true,
 * so {@code locateAll} returned zero sections despite a geometrically regular 7-column
 * transaction table. Every fixture below is fully hand-synthesized -- invented names, dates and
 * amounts that satisfy a self-consistent balance chain -- per the Synthetic Fixture Policy; no
 * value from the real document appears here, redacted or otherwise.
 *
 * <p>The geometry mirrors the real document's shape at a coarser scale: Date and Value Date as
 * narrow left-aligned columns, a wide left-aligned Narration column, then Debit/Credit/Balance as
 * right-aligned amount columns further right, spaced well beyond
 * {@code HEADERLESS_COLUMN_CLUSTER_TOLERANCE} so column discovery cannot conflate them.
 */
class HeaderlessLayoutInferenceTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    /** A right-aligned amount cell ending at {@code endX}, wide enough for its own text. */
    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return run(text, endX - width, width, y);
    }

    /** One transaction line: Date, Value Date (same value), Narration, then Debit/Credit/Balance
     *  at their fixed right edges. Either debitText or creditText is "-" for every real row, same
     *  as the real document's placeholder for the side that didn't move. Column right edges are
     *  spaced 130pt apart -- matching the real document's own measured separation between its
     *  Debit and Credit value clusters (their POPULATED left edges, not just their anchors, sit
     *  roughly 60-90pt apart) -- so a narrow placeholder dash's left edge is never ambiguous
     *  between neighbouring columns the way a tighter, unrealistic spacing would make it. */
    private static List<PositionedText> transaction(String date, String narration, String debitText,
                                                      String creditText, String balanceText, float y) {
        List<PositionedText> row = new ArrayList<>();
        row.add(run(date, 30f, 55f, y));
        row.add(run(date, 95f, 55f, y));
        row.add(run(narration, 165f, narration.length() * 5.2f, y));
        row.add(amount(debitText, 390f, y));
        row.add(amount(creditText, 520f, y));
        row.add(amount(balanceText, 650f, y));
        return row;
    }

    /** A five-transaction, purely debit/credit/balance-consistent statement -- the shape the
     *  motivating real document has. Opening balance (10,000.00, not itself asserted -- the chain
     *  only checks consecutive deltas) then: -500 debit, +20,000 credit, -1,500 debit, -300 debit,
     *  +200 credit. */
    private static List<List<PositionedText>> baselineTransactions() {
        List<List<PositionedText>> rows = new ArrayList<>();
        rows.add(transaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f));
        rows.add(transaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f));
        rows.add(transaction("03/01/2026", "ELECTRICITY BILL PAYMENT ONLINE", "1500.00", "-", "28000.00", 340f));
        rows.add(transaction("04/01/2026", "MOBILE RECHARGE PREPAID PLAN", "300.00", "-", "27700.00", 360f));
        rows.add(transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 380f));
        return rows;
    }

    @Test
    void headerlessStatement_infersColumnsAndProducesTransactions() {
        List<PositionedText> positioned = new ArrayList<>();
        List<List<PositionedText>> rows = baselineTransactions();
        // A wrapped narration continuation for the first transaction -- no date, no amount, same
        // narration x -- recovered via the reused mergeInto, exactly like WRAPPED_DESCRIPTION.
        rows.add(1, List.of(run("REF ID ABCDE123", 165f, 80f, 308f)));
        for (List<PositionedText> row : rows) positioned.addAll(row);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<java.util.Map<String, String>> staged = doc.sections().get(0).rows();
        assertThat(staged).hasSize(5);
        assertThat(staged.get(0)).containsEntry("Debit", "500.00").containsEntry("Description",
                "GROCERY STORE PURCHASE MONTHLY REF ID ABCDE123");
        assertThat(staged.get(1)).containsEntry("Credit", "20000.00");
        assertThat(staged.get(2)).containsEntry("Debit", "1500.00");
        assertThat(staged.get(4)).containsEntry("Credit", "200.00").containsEntry("Balance", "27900.00");
        List<String> capabilities = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(capabilities).contains("INFERRED_HEADERLESS_LAYOUT");
        assertThat(capabilities)
                .as("this baseline document has no repeated row -- the capability marker must not "
                        + "fire just because the headerless path ran, only when it actually removes one")
                .doesNotContain("PHYSICAL_ROW_DEDUP_EVIDENCE");
    }

    @Test
    void adjacentDuplicateRow_isDroppedFromOutputAndScoring() {
        List<PositionedText> positioned = new ArrayList<>();
        List<List<PositionedText>> rows = baselineTransactions();
        // The real document's own artifact: its last transaction reprinted verbatim at the top of
        // the next page. Placed immediately after the real occurrence, same as the real document.
        rows.add(transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 388f));
        for (List<PositionedText> row : rows) positioned.addAll(row);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        // 5 real transactions, not 6 -- the reprint is dropped, not staged as a phantom sixth row.
        assertThat(doc.sections().get(0).rows()).hasSize(5);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("INFERRED_HEADERLESS_LAYOUT");
    }

    @Test
    void adjacentDuplicateRow_isRecordedAsRowAccountingEvidenceRatherThanSilentlyDropped() {
        // Same fixture as adjacentDuplicateRow_isDroppedFromOutputAndScoring -- this asserts the
        // Input Fate Accounting side of the same drop: the reprint must not just be absent from
        // output, it must leave a trace RowAccountingValidator can turn into a WARNING.
        List<PositionedText> positioned = new ArrayList<>();
        List<List<PositionedText>> rows = baselineTransactions();
        rows.add(transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 388f));
        for (List<PositionedText> row : rows) positioned.addAll(row);

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        List<PdfTableLocator.DroppedCandidateRow> dropped =
                doc.sections().get(0).evidence().droppedTransactionCandidates();
        assertThat(dropped).hasSize(1);
        assertThat(dropped.get(0).reason()).isEqualTo("REPEATED_PHYSICAL_ROW_REMOVED");
        assertThat(dropped.get(0).signals()).containsExactlyInAnyOrder("DATE_PRESENT", "AMOUNT_PRESENT");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .as("recorded only because a removal actually happened, not merely because this "
                        + "code path ran -- a future corpus sweep can distinguish 'path exercised, "
                        + "nothing to remove' from 'path exercised, this safety net fired'")
                .contains("PHYSICAL_ROW_DEDUP_EVIDENCE");
    }

    @Test
    void documentWithARealHeader_neverReachesInference() {
        // An ordinary header-based table -- proves this capability is unreachable once the
        // existing header path already finds something, regardless of how the fallback behaves.
        List<PositionedText> runs = new ArrayList<>(List.of(
                run("Date", 30f, 30f, 200f),
                run("Description", 100f, 60f, 200f),
                run("Amount", 400f, 40f, 200f),
                run("Balance", 480f, 40f, 200f)));
        runs.addAll(List.of(
                run("01/01/2026", 30f, 55f, 220f),
                run("COFFEE SHOP", 100f, 60f, 220f),
                run("50.00", 400f, 30f, 220f),
                run("9950.00", 480f, 40f, 220f)));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(runs, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("INFERRED_HEADERLESS_LAYOUT");
    }

    @Test
    void tooManyNumericCandidates_bailsToZeroSections() {
        // 3 transaction-shaped rows, each with 6 populated decimal-amount columns beyond Date and
        // Description -- one becomes Balance, leaving 5, past HEADERLESS_MAX_NUMERIC_CANDIDATES(4).
        List<PositionedText> positioned = new ArrayList<>();
        float[] endXs = {250f, 300f, 350f, 400f, 450f, 500f};
        String[] dates = {"01/01/2026", "02/01/2026", "03/01/2026"};
        for (int r = 0; r < dates.length; r++) {
            float y = 300f + r * 20f;
            positioned.add(run(dates[r], 30f, 55f, y));
            positioned.add(run("SOME TRANSACTION NARRATIVE TEXT", 100f, 100f, y));
            for (float endX : endXs) {
                positioned.add(amount(String.format("%d.00", 10 + r), endX, y));
            }
        }

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).isEmpty();
    }

    @Test
    void chainScoreBelowThreshold_bailsRatherThanGuessingWrong() {
        // Balance column present on every row but internally inconsistent with either candidate
        // Debit/Credit assignment -- neither permutation should clear the acceptance threshold.
        List<PositionedText> positioned = new ArrayList<>();
        record Row(String date, String desc, String a, String b, String balance, float y) {}
        List<Row> rows = List.of(
                new Row("01/01/2026", "ONE", "100.00", "-", "5000.00", 300f),
                new Row("02/01/2026", "TWO", "200.00", "-", "1234.56", 320f),
                new Row("03/01/2026", "THREE", "-", "300.00", "9999.99", 340f),
                new Row("04/01/2026", "FOUR", "50.00", "-", "42.00", 360f));
        for (Row r : rows) {
            positioned.add(run(r.date(), 30f, 55f, r.y()));
            positioned.add(run(r.desc(), 100f, 60f, r.y()));
            positioned.add(amount(r.a(), 300f, r.y()));
            positioned.add(amount(r.b(), 380f, r.y()));
            positioned.add(amount(r.balance(), 460f, r.y()));
        }

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).isEmpty();
    }

    /** Two genuine transactions (opening 10,000.00, then -500 and +20,000 -- see
     *  {@link #baselineTransactions()} -- ending at 29,500.00) plus one more row built from the
     *  SAME column geometry, whose Debit cell is {@code decoyDebitText} and whose Balance is
     *  {@code decoyBalanceText}, placed where a real Debit value would sit so an accepted decoy
     *  participates in the SAME columns as the real rows rather than being rejected later for
     *  unrelated geometry reasons. */
    private static List<PositionedText> positionedWithDecoyRow(String decoyDebitText, String decoyBalanceText) {
        List<PositionedText> positioned = new ArrayList<>();
        for (List<PositionedText> row : baselineTransactions().subList(0, 2)) positioned.addAll(row);
        positioned.addAll(transaction("06/01/2026", "ACCOUNT NUMBER REFERENCE ENTRY", decoyDebitText, "-", decoyBalanceText, 400f));
        return positioned;
    }

    @Test
    void accountNumberBanner_isNotMisreadAsATransactionRow() {
        // Only 2 genuine transactions -- below HEADERLESS_MIN_TRANSACTION_ROWS(3) on their own --
        // plus one decoy row whose Debit cell is a bare account-number-shaped digit run, no
        // decimal point (its Balance is deliberately inconsistent with the real chain -- with
        // either guard working, that value is never even reached for scoring). Two independent
        // decimal-point guards can each account for this staying empty: isTransactionShapedRow's
        // own (a row without a decimal-shaped cell never becomes a candidate at all) and
        // ColumnStats.numericPurity's (even if the row were counted, a column whose non-blank
        // cells are mostly non-decimal never qualifies as a numeric candidate) -- deliberate
        // defense in depth, both mirroring the same guard bucketRow's own OFFSET_COLUMN_ANCHORS
        // redirect already applies. This test asserts the OUTCOME (a bare account number never
        // produces a false transaction), not which guard specifically produced it. The control
        // below is the differential proof that decimal-shaped, chain-consistent data of the same
        // shape and row count DOES get through -- so this test's empty result is the missing
        // decimal point, not an incidental rejection unrelated to amount-shape at all.
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator()
                .locateAll(positionedWithDecoyRow("15000", "1.00"), ctx);

        assertThat(doc.sections()).isEmpty();
    }

    @Test
    void isTransactionShapedRow_control_sameDecoyWithADecimalPointIsCounted() {
        // Differential control for accountNumberBanner_isNotMisreadAsATransactionRow: identical
        // geometry and row count, the decoy's Debit value now decimal-shaped AND chain-consistent
        // (29,500.00 - 150.00 = 29,350.00). This DOES reach HEADERLESS_MIN_TRANSACTION_ROWS(3) and
        // DOES produce rows -- proof that the previous test's empty result tracks the decimal
        // point specifically, not some incidental rejection elsewhere in the pipeline (e.g.
        // clustering, description detection) that would have excluded the decoy regardless of its
        // amount shape.
        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator()
                .locateAll(positionedWithDecoyRow("150.00", "29350.00"), ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(3);
    }
}
