package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * COMPOSITE_STATEMENT via a plain identity line: a composite PDF containing two genuinely
 * different accounts must never have the second account's transactions silently appended into
 * the first account's history, even when nothing but an ordinary "Account Number: &lt;digits&gt;"
 * line (no {@link PdfTableLocator#SECTION_MARKER}-shaped banner) separates them and both tables
 * share the exact same column layout.
 *
 * <p>Real bug, found by adversarial architecture review: {@code PdfTableLocator}'s header-diff
 * fallback decided "same table, keep appending" purely from column-name equality
 * ({@code headerSignature()}), with zero account-identity signal. Two different accounts sharing
 * a layout, each named only by a plain identity line, were silently merged into one account's
 * transaction history -- wrong balances, wrong categorization, wrong everything downstream, with
 * no error and nothing for the user to notice.
 *
 * <p><b>The design this class protects is deliberately NOT "identity line appears -&gt; new
 * account starts here."</b> That was the first implementation, and it broke a real document
 * (hdfc-composite-deposit-schedules -- see {@link #trailingIdentityRestatement_keepsItsMetadata_doesNotStealTheNextSections}):
 * an "Account Number:" line restating the SAME account as trailing text, before a genuinely
 * different, differently-shaped table, is a common real shape, not a corner case. Closing the
 * section immediately at that line misattributed its trailing content to the wrong side of the
 * real boundary. The actual model is:
 *
 * <pre>
 * identity line appears
 *        |
 *        v
 * remember it (do NOT close anything yet)
 *        |
 *        v
 * wait for the next header -- the point sections are actually created
 *        |
 *        v
 * same shape as the still-open section AND the identity didn't confirm as a repeat?
 *   -&gt; split (this is the actual danger zone: two accounts sharing one layout)
 * different shape, or the identity DID confirm as a repeat?
 *   -&gt; whatever the header-diff fallback would already have decided on its own
 * </pre>
 *
 * <p>Every fixture below is fully hand-synthesized -- invented account numbers, names, and
 * amounts -- per the Synthetic Fixture Policy; no value from any real document appears here.
 */
class AccountIdentityLinePdfTableLocatorTest {

    private static PositionedText run(String text, float x, float width, float y) {
        return new PositionedText(text, x, y, 0, width);
    }

    private static PositionedText line(String text, float y) {
        // Fixed width, not derived from text length: PdfTraceRedactor-adjacent fixtures elsewhere
        // in this package use the same fixed-width convention for a free-standing line, and a
        // computed width here was found (in a since-discarded version of this file) to interact
        // with LEADING_NARRATION_CONTINUATION's own lookahead in a way narrower/shorter identity
        // lines don't -- unrelated to anything this class tests, so sidestepped rather than chased.
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

    // ==================== Test 1: the original P0 -- same layout, different accounts ====================

    @Test
    void sameColumnLayout_differentAccountNumbers_neverMergesIntoOneSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("Account Number: 111111111111", 90f));
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Amazon", "100.00", "900.00", 130f));
        positioned.add(line("Account Number: 222222222222", 150f));
        positioned.addAll(ledgerHeader(170f));
        positioned.addAll(ledgerRow("02.01.2026", "Flipkart", "200.00", "700.00", 190f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("two accounts, never silently merged").hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(0).rows().get(0)).containsEntry("Description", "Amazon");
        assertThat(doc.sections().get(1).rows()).hasSize(1);
        assertThat(doc.sections().get(1).rows().get(0)).containsEntry("Description", "Flipkart");
        assertThat(ctx.capabilities().stream().map(c -> c.capability())).contains("COMPOSITE_STATEMENT");
    }

    // ==================== Test 2: same account, repeated across a page break ====================

    @Test
    void sameAccountNumberRepeated_acrossAPageBreak_staysOneSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("Account Number: 111111111111", 90f));
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "Page1 txn 1", "500.00", "9500.00", 130f));
        positioned.addAll(ledgerRow("02.01.2026", "Page1 txn 2", "600.00", "8900.00", 150f));
        // Page-break-repeated identity line, identical text/formatting to the first.
        positioned.add(line("Account Number: 111111111111", 170f));
        positioned.addAll(ledgerHeader(190f));
        positioned.addAll(ledgerRow("05.01.2026", "Page2 txn 1", "300.00", "8600.00", 210f));
        positioned.addAll(ledgerRow("06.01.2026", "Page2 txn 2", "400.00", "8200.00", 230f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("one account repeated on a new page, not over-split").hasSize(1);
        assertThat(doc.sections().get(0).rows()).hasSize(4);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("REPEATED_ACCOUNT_BANNER", "REPEATED_HEADER")
                .doesNotContain("COMPOSITE_STATEMENT");
    }

    // ==================== Test 3: trailing identity restatement (the regression) ====================

    /**
     * The design lesson this whole class exists to encode. Modeled on the real bug found in
     * {@code hdfc-composite-deposit-schedules}: a fixed-deposit schedule's trailing content
     * restates its OWN account number before a genuinely different, differently-shaped
     * recurring-deposit table begins. An identity line is not itself a section boundary -- only
     * the next header event is. Closing immediately at the identity line (this class's first,
     * broken implementation) misattributed that restatement to the wrong side of the real
     * boundary: the FD section lost the very line that identifies it, and the RD section gained
     * aux text that was never its own.
     */
    @Test
    void trailingIdentityRestatement_keepsItsMetadata_doesNotStealTheNextSections() {
        // Header vocabulary chosen so both rows clear looksLikeHeaderRow's own bar (>= 2 cells
        // matching HEADER_HINTS, a date-shaped cell among them, and dense enough not to read as
        // prose) -- and so FD's and RD's normalized column sets genuinely differ, the same way a
        // real FD schedule's columns differ from a real RD schedule's.
        List<PositionedText> fdHeader = new ArrayList<>();
        fdHeader.add(run("Principal Amount", 70f, 70f, 0f));
        fdHeader.add(run("Start Date", 200f, 50f, 0f));
        fdHeader.add(run("Maturity Date", 380f, 60f, 0f));
        fdHeader.add(run("Rate of Interest", 480f, 60f, 0f));

        List<PositionedText> rdHeader = new ArrayList<>();
        rdHeader.add(run("Installment Amount", 70f, 70f, 0f));
        rdHeader.add(run("Due Date", 200f, 50f, 0f));
        rdHeader.add(run("Maturity Date", 380f, 60f, 0f));
        rdHeader.add(run("Status", 480f, 30f, 0f));

        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("Account Holder: JOHN", 70f));
        positioned.add(line("Account Number: 111111", 90f));
        for (PositionedText t : fdHeader) positioned.add(withY(t, 110f));
        positioned.add(run("50000.00", 71f, 50f, 130f));
        positioned.add(run("12/03/2026", 201f, 50f, 130f));
        positioned.add(run("12/03/2027", 381f, 50f, 130f));
        positioned.add(run("7.10", 481f, 30f, 130f));
        // Trailing restatement of the SAME account, as plain aux text, before a genuinely
        // different table -- not a new account starting.
        positioned.add(line("Account Number: 111111", 150f));
        for (PositionedText t : rdHeader) positioned.add(withY(t, 170f));
        positioned.add(run("1", 71f, 20f, 190f));
        positioned.add(run("05/05/2027", 201f, 50f, 190f));
        positioned.add(run("5000.00", 381f, 40f, 190f));
        positioned.add(run("Paid", 481f, 30f, 190f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).as("FD and RD are structurally different tables").hasSize(2);
        assertThat(doc.sections().get(0).rows()).hasSize(1);
        assertThat(doc.sections().get(1).rows()).hasSize(1);

        assertThat(doc.sections().get(0).auxiliaryText())
                .as("the FD section keeps the identity line that restates its own account -- "
                        + "this is what the original close-immediately design lost")
                .anyMatch(l -> l.contains("Account Number: 111111"));
        assertThat(doc.sections().get(1).auxiliaryText())
                .as("the RD section must not steal the FD section's trailing identity text")
                .noneMatch(l -> l.contains("Account Number: 111111"));
    }

    private static PositionedText withY(PositionedText t, float y) {
        return new PositionedText(t.text(), t.x(), y, t.pageIndex(), t.width());
    }

    // ==================== Test 4: identity mismatch overrides "same shape, keep appending" ====================

    /**
     * Isolates the actual danger zone from Test 1's full real-world shape: an identity mismatch,
     * immediately followed by a header of the EXACT SAME shape as the still-open section, with no
     * other distinguishing signal at all (no blank line, no different column, nothing). This is
     * the specific branch the header-diff fallback's {@code identityContradicts} check exists for
     * -- without it, this would fall straight into "REPEATED_HEADER, keep appending" and merge.
     *
     * <p>The original plan sketched a three-way verdict (same account / different account /
     * AMBIGUOUS) for a genuinely unparseable second identity (e.g. "Account Number: unknown").
     * That verdict belongs to the future Section Identity Resolver (Layer 2), which sees real
     * identity/product/institution signals this layer never has -- not here.
     * {@link PdfTableLocator#ACCOUNT_IDENTITY_LINE} itself requires 4+ digits to match at all, so
     * a value that fails to parse as a plain identity line is never structural evidence in the
     * first place; this layer only ever has two outcomes, confirmed-same or not-confirmed, and its
     * whole job is to never guess the second one into a merge. That is what this test asserts.
     */
    @Test
    void identityMismatch_immediatelyFollowedByTheSameHeaderShape_stillNeverMerges() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(line("Account Number: 111111", 90f));
        positioned.addAll(ledgerHeader(110f));
        positioned.addAll(ledgerRow("01.01.2026", "First account txn", "100.00", "900.00", 130f));
        // Different account, then the SAME header shape immediately after -- no blank line, no
        // other structural difference at all.
        positioned.add(line("Account Number: 999999", 150f));
        positioned.addAll(ledgerHeader(170f));
        positioned.addAll(ledgerRow("02.01.2026", "Second account txn", "200.00", "700.00", 190f));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections())
                .as("a contradicting identity must override same-shape-header REPEATED_HEADER handling")
                .hasSize(2);
        assertThat(doc.sections().get(0).rows().get(0)).containsEntry("Description", "First account txn");
        assertThat(doc.sections().get(1).rows().get(0)).containsEntry("Description", "Second account txn");
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .as("split via the identity-contradiction path, not a repeated-header continuation")
                .contains("COMPOSITE_STATEMENT")
                .doesNotContain("REPEATED_HEADER");
    }
}
