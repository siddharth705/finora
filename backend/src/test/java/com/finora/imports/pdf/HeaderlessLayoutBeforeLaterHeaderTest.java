package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HEADERLESS_LAYOUT_BEFORE_LATER_HEADER: a genuinely headerless transaction table sitting BEFORE a
 * completely unrelated table further down the same document whose OWN header IS recognized.
 *
 * <p>Found on a real HSBC credit-card statement: its transaction table has no header row anywhere
 * (no "Date/Description/Amount" vocabulary at all), but a later page carries a real, unrelated EMI
 * "Loan Summary Table" with a genuine header ("Loan Booking Date | Principal | Interest | ..."). The
 * main header-scanning loop finds that later header first, so {@code sections} is never empty --
 * the {@code sections.isEmpty()}-gated headerless fallback ({@link HeaderlessLayoutInferenceTest})
 * never runs at all, regardless of how many real transactions came before the false header. Every
 * fixture below is fully hand-synthesized -- invented dates/amounts/narration -- per the Synthetic
 * Fixture Policy already established in {@link HeaderlessLayoutInferenceTest}; no value from the
 * real document appears here, redacted or otherwise.
 */
class HeaderlessLayoutBeforeLaterHeaderTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText amount(String text, float endX, float y) {
        float width = text.length() * 6.2f;
        return run(text, endX - width, width, y);
    }

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

    private static PositionedText onPage(int page, String text, float x, float width, float y) {
        return new PositionedText(text, x, y, page, width);
    }

    @Test
    void headerlessTransactions_beforeALaterUnrelatedHeader_areRecoveredAsTheirOwnLeadingSection() {
        List<PositionedText> positioned = new ArrayList<>();
        // 5 genuine transactions, headerless, all on page 0 -- clears HEADERLESS_MIN_TRANSACTION_ROWS
        // on its own, so this test isolates the "where do we even look" mechanism from the separate
        // balance-reconciliation floor override.
        List<List<PositionedText>> transactions = List.of(
                transaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f),
                transaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f),
                transaction("03/01/2026", "ELECTRICITY BILL PAYMENT ONLINE", "1500.00", "-", "28000.00", 340f),
                transaction("04/01/2026", "MOBILE RECHARGE PREPAID PLAN", "300.00", "-", "27700.00", 360f),
                transaction("05/01/2026", "REFUND FROM ONLINE MERCHANT STORE", "-", "200.00", "27900.00", 380f));
        for (List<PositionedText> row : transactions) positioned.addAll(row);

        // A later, unrelated real table on page 1 -- its own genuine header, genuinely unrelated to
        // the transactions above. Needs BOTH "Loan Booking Date" (matches the DATE_HINTS "date"
        // token) AND "Installment amount" (matches HEADER_HINTS' "amount" token) to itself clear
        // looksLikeHeaderRow's own >=2-hint-match/density floor -- a 3-column header using only
        // "Principal"/"Merchant Name" alongside the date column scores just 1 match and is never
        // recognized as a header at all, which was this test's own first-draft bug.
        positioned.add(onPage(1, "Loan Booking Date", 30f, 60f, 56f));
        positioned.add(onPage(1, "Principal", 200f, 50f, 56f));
        positioned.add(onPage(1, "Installment amount", 350f, 70f, 56f));
        positioned.add(onPage(1, "Merchant Name", 450f, 60f, 56f));
        positioned.add(onPage(1, "01 Mar 2026", 30f, 55f, 76f));
        positioned.add(onPage(1, "5000.00", 200f, 40f, 76f));
        positioned.add(onPage(1, "350.00", 350f, 40f, 76f));
        positioned.add(onPage(1, "SOME MERCHANT LTD", 450f, 70f, 76f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(2);
        // The recovered pre-header section comes FIRST -- it physically precedes the later table.
        List<Map<String, String>> recovered = doc.sections().get(0).rows();
        assertThat(recovered).hasSize(5);
        assertThat(recovered.get(0)).containsEntry("Debit", "500.00");
        assertThat(recovered.get(4)).containsEntry("Credit", "200.00").containsEntry("Balance", "27900.00");
        // The later table's own section is still found too -- this fix only ADDS a section, it
        // never displaces or merges with what the main loop already found.
        assertThat(doc.sections().get(1).rows().get(0)).containsEntry("Merchant Name", "SOME MERCHANT LTD");
        List<String> capabilities = ctx.capabilities().stream().map(c -> c.capability()).toList();
        assertThat(capabilities).contains("HEADERLESS_LAYOUT_BEFORE_LATER_HEADER", "INFERRED_HEADERLESS_LAYOUT");
    }

    @Test
    void noContentBeforeTheFirstHeader_neverAttemptsThePreHeaderPath() {
        // The header is the very first row in the document -- firstHeaderRowIndex is 0, not > 0, so
        // the new pre-header path must never even run. Differential control proving the fix is
        // inert on an ordinary, already-working header-based document.
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
                .doesNotContain("HEADERLESS_LAYOUT_BEFORE_LATER_HEADER");
    }

    @Test
    void tooFewGenuinePreHeaderTransactions_withNoBalanceCorroboration_findsNothing() {
        // Only 2 headerless transactions before the later header -- below
        // HEADERLESS_MIN_TRANSACTION_ROWS(3) -- and no printed OPENING/NET OUTSTANDING BALANCE
        // labels anywhere to corroborate them (see HeaderlessLayoutInferenceTest's own
        // balance-reconciliation tests for that separate mechanism). The pre-header path is
        // reached, but inferHeaderlessSection itself still declines, same as it always has.
        List<PositionedText> positioned = new ArrayList<>();
        List<List<PositionedText>> transactions = List.of(
                transaction("01/01/2026", "GROCERY STORE PURCHASE MONTHLY", "500.00", "-", "9500.00", 300f),
                transaction("02/01/2026", "SALARY CREDIT FROM EMPLOYER LTD", "-", "20000.00", "29500.00", 320f));
        for (List<PositionedText> row : transactions) positioned.addAll(row);

        positioned.add(onPage(1, "Loan Booking Date", 30f, 60f, 56f));
        positioned.add(onPage(1, "Principal", 200f, 50f, 56f));
        positioned.add(onPage(1, "Installment amount", 350f, 70f, 56f));
        positioned.add(onPage(1, "01 Mar 2026", 30f, 55f, 76f));
        positioned.add(onPage(1, "5000.00", 200f, 40f, 76f));
        positioned.add(onPage(1, "350.00", 350f, 40f, 76f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        assertThat(doc.sections().get(0).rows().get(0)).containsEntry("Principal", "5000.00");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .doesNotContain("HEADERLESS_LAYOUT_BEFORE_LATER_HEADER");
    }
}
